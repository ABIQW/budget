package dev.budget;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * /budget 10m
 * Downloads the Bazaar ONE time (only when the command is run), picks ONE item,
 * and suggests: Buy Order price, quantity, Sell Offer price, profit.
 * No auto buy/sell. No timers. No background requests. No cache.
 */
public class BudgetMod implements ClientModInitializer {

    private static final String URL = "https://api.hypixel.net/v2/skyblock/bazaar";
    private static final double FEE = 0.0125;        // estimated Bazaar fee (not verified)
    private static final double MIN_DAILY = 3000;    // item must trade at least this many per day
    private static final double DAILY_SHARE = 0.10;  // never order more than 10% of a day's trading

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "budget-mod");
        t.setDaemon(true);
        return t;
    });

    @Override
    public void onInitializeClient() {
        // Only registers the command. NO network request here.
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("budget")
                        .executes(ctx -> {
                            msg(ctx.getSource(), "Usage: /budget 10m   (k = thousand, m = million, b = billion)");
                            return 1;
                        })
                        .then(ClientCommandManager.argument("amount", StringArgumentType.word())
                                .executes(ctx -> {
                                    FabricClientCommandSource src = ctx.getSource();
                                    double budget = parseBudget(StringArgumentType.getString(ctx, "amount"));
                                    if (budget < 1000) {
                                        msg(src, "Invalid budget. Example: /budget 10m (minimum 1k)");
                                        return 0;
                                    }
                                    msg(src, "Downloading Bazaar now (one fresh request)...");
                                    EXEC.submit(() -> run(src, budget));
                                    return 1;
                                }))));
    }

    private static void run(FabricClientCommandSource src, double budget) {
        try {
            JsonObject root = fetch();
            if (!root.has("success") || !root.get("success").getAsBoolean()) {
                msg(src, "Bazaar API did not return valid data. Try again later.");
                return;
            }
            Pick best = analyze(root.getAsJsonObject("products"), budget);
            if (best == null) {
                msg(src, "No good option found for this budget right now.");
                return;
            }
            show(src, budget, best);
        } catch (Exception e) {
            msg(src, "Error: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
    }

    private static JsonObject fetch() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(URL))
                .timeout(Duration.ofSeconds(30))
                .header("Accept-Encoding", "gzip")
                .header("User-Agent", "BudgetMod/1.0")
                .GET().build();
        HttpResponse<InputStream> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() != 200) {
            throw new RuntimeException("HTTP " + res.statusCode());
        }
        InputStream in = res.body();
        String enc = res.headers().firstValue("Content-Encoding").orElse("");
        if (enc.equalsIgnoreCase("gzip")) {
            in = new GZIPInputStream(in);
        }
        try (InputStreamReader reader = new InputStreamReader(in)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static class Pick {
        String name;
        double instantBuy, buyOrder, sellOffer, cost, gross, fees, net, roi, perHour, waitBuyH, waitSellH;
        int qty;
    }

    private static Pick analyze(JsonObject products, double budget) {
        Pick best = null;
        for (Map.Entry<String, JsonElement> e : products.entrySet()) {
            try {
                JsonObject p = e.getValue().getAsJsonObject();
                JsonArray sellSummary = p.getAsJsonArray("sell_summary"); // existing buy orders (best first)
                JsonArray buySummary = p.getAsJsonArray("buy_summary");   // existing sell offers (best first)
                if (sellSummary.size() == 0 || buySummary.size() == 0) continue;
                JsonObject qs = p.getAsJsonObject("quick_status");

                double topBuyOrder = sellSummary.get(0).getAsJsonObject().get("pricePerUnit").getAsDouble();
                double lowSellOffer = buySummary.get(0).getAsJsonObject().get("pricePerUnit").getAsDouble();
                if (topBuyOrder <= 0 || lowSellOffer <= 0) continue;

                double buy = round1(topBuyOrder + 0.1);   // our Buy Order: just above best buy order
                double sell = round1(lowSellOffer - 0.1); // our Sell Offer: just below cheapest sell offer
                if (sell <= buy) continue;

                double netUnit = sell * (1 - FEE) - buy;
                if (netUnit <= 0) continue;

                double fillBuyDaily = qs.get("sellMovingWeek").getAsDouble() / 7.0;  // fills our buy order
                double fillSellDaily = qs.get("buyMovingWeek").getAsDouble() / 7.0;  // fills our sell offer
                if (fillBuyDaily < MIN_DAILY || fillSellDaily < MIN_DAILY) continue;

                long qty = (long) Math.floor(budget / buy);
                qty = Math.min(qty, (long) Math.floor(DAILY_SHARE * Math.min(fillBuyDaily, fillSellDaily)));
                if (qty < 1) continue;

                double cost = buy * qty;
                double gross = sell * qty;
                double fees = gross * FEE;
                double net = gross - cost - fees;
                if (net <= 0 || cost > budget) continue;

                double waitBuyH = qty / (fillBuyDaily / 24.0);
                double waitSellH = qty / (fillSellDaily / 24.0);
                double perHour = net / Math.max(0.1, waitBuyH + waitSellH);

                if (best == null || perHour > best.perHour) {
                    Pick k = new Pick();
                    k.name = pretty(e.getKey());
                    k.instantBuy = lowSellOffer;
                    k.buyOrder = buy; k.sellOffer = sell; k.qty = (int) qty;
                    k.cost = cost; k.gross = gross; k.fees = fees; k.net = net;
                    k.roi = net / cost * 100.0; k.perHour = perHour;
                    k.waitBuyH = waitBuyH; k.waitSellH = waitSellH;
                    best = k;
                }
            } catch (Exception ignored) {
                // skip items with incomplete data
            }
        }
        return best;
    }

    private static void show(FabricClientCommandSource src, double budget, Pick p) {
        msg(src, "=== Budget: best item ===");
        msg(src, "Budget: " + n(budget));
        msg(src, "Item: " + p.name);
        msg(src, "--- Step 1: Buy Order ---");
        msg(src, "Instant buy price now: " + d(p.instantBuy) + " (not recommended)");
        msg(src, "Buy Order price: " + d(p.buyOrder));
        msg(src, "Quantity: " + n(p.qty));
        msg(src, "Total cost: " + n(p.cost));
        msg(src, "Remaining budget: " + n(budget - p.cost));
        msg(src, "Estimated fill time: " + t(p.waitBuyH));
        msg(src, "--- Step 2: Sell Offer (after order is filled) ---");
        msg(src, "Sell Offer price: " + d(p.sellOffer));
        msg(src, "Gross sales: " + n(p.gross));
        msg(src, "Estimated fees (1.25%): " + n(p.fees));
        msg(src, "Estimated net profit: " + n(p.net));
        msg(src, "ROI: " + String.format("%.2f", p.roi) + "%");
        msg(src, "Estimated sell time: " + t(p.waitSellH));
        msg(src, "Estimate only. Prices change; profit is not guaranteed.");
    }

    private static void msg(FabricClientCommandSource src, String text) {
        Minecraft.getInstance().execute(() -> src.sendFeedback(Component.literal(text)));
    }

    private static double parseBudget(String s) {
        Matcher m = Pattern.compile("^(\\d+(?:\\.\\d+)?)([kmb]?)$", Pattern.CASE_INSENSITIVE)
                .matcher(s.trim());
        if (!m.matches()) return -1;
        double v = Double.parseDouble(m.group(1));
        switch (m.group(2).toLowerCase()) {
            case "k": return v * 1_000;
            case "m": return v * 1_000_000;
            case "b": return v * 1_000_000_000;
            default: return v;
        }
    }

    private static double round1(double v) { return Math.round(v * 10.0) / 10.0; }
    private static String n(double v) { return String.format("%,.0f", v); }
    private static String d(double v) { return String.format("%,.1f", v); }

    private static String t(double hours) {
        double min = hours * 60.0;
        if (min < 1) return "under 1 minute";
        if (min < 60) return "about " + Math.round(min) + " min";
        return "about " + String.format("%.1f", hours) + " hours";
    }

    private static String pretty(String id) {
        String[] parts = id.toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1)).append(' ');
        }
        return sb.toString().trim();
    }
}
