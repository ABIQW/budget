package dev.budget;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
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

public class BudgetMod implements ClientModInitializer {

    private static final String URL = "https://api.hypixel.net/v2/skyblock/bazaar";
    private static final double FEE = 0.0125;

    // ---------------------------------------------------------------------
    // Liquidity settings (v1.1). Change these numbers to make the filter
    // stricter or looser. Everything is "per side": Buy Order side and
    // Sell Offer side must BOTH be active.
    // ---------------------------------------------------------------------

    /** Min units/day that must be instant-sold (fills Buy Orders) AND instant-bought (fills Sell Offers). */
    private static final double MIN_DAILY = 4000.0;

    /** Min number of open orders on each side of the book (skipped if the API does not give it). */
    private static final double MIN_ORDERS = 8.0;

    /** Max allowed Sell Offer / Buy Order price ratio. A huge gap = thin market, not a real flip. */
    private static final double MAX_SPREAD_RATIO = 1.5;

    /** Above this ratio the score is reduced (wide gap = less trustworthy). */
    private static final double SPREAD_PENALTY_START = 1.15;

    /** Target time (minutes) for the Buy Order and for the Sell Offer to fill. Quantity is sized to this. */
    private static final double TARGET_FILL_MIN = 10.0;

    /** Reject the item if the whole cycle (buy + sell) is estimated slower than this (minutes). */
    private static final double MAX_CYCLE_MIN = 25.0;

    /** Competitors in the top price levels that share the flow with you. */
    private static final int TOP_LEVELS = 3;

    /** Minimum useful trade. */
    private static final double MIN_ROI = 0.5;
    private static final double MIN_NET = 50.0;

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread th = new Thread(r, "budget-mod");
        th.setDaemon(true);
        return th;
    });

    private static final class Pick {
        String name;
        double instantBuy;
        double buyOrder;
        double sellOffer;
        double cost;
        double gross;
        double fees;
        double net;
        double roi;
        double perHour;
        double waitBuyH;
        double waitSellH;
        double flowBuyDay;   // units/day that instant-sell into Buy Orders
        double flowSellDay;  // units/day that instant-buy from Sell Offers
        double compBuy;      // orders competing at the top Buy Order levels
        double compSell;     // orders competing at the top Sell Offer levels
        double spreadPct;
        int qty;

        private Pick() {
        }
    }

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("budget")
                        .executes(ctx -> {
                            msg(ctx.getSource(), "Usage: /budget 10m   (k = thousand, m = million, b = billion)");
                            return 1;
                        })
                        .then(ClientCommands.argument("amount", StringArgumentType.word())
                                .executes(BudgetMod::onBudget))));
    }

    private static int onBudget(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource src = ctx.getSource();
        double budget = parseBudget(StringArgumentType.getString(ctx, "amount"));
        if (budget < 1000.0) {
            msg(src, "Invalid budget. Example: /budget 10m (minimum 1k)");
            return 0;
        }
        msg(src, "Downloading Bazaar now (one fresh request)...");
        EXEC.submit(() -> run(src, budget));
        return 1;
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
                msg(src, "Only items with real demand (fast Buy Order AND fast Sell Offer) are shown.");
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
                .GET()
                .build();
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

    /**
     * Picks the best flip. Liquidity comes first:
     *  1. Both sides of the market must trade a lot (units/day).
     *  2. The Sell/Buy gap must be realistic (no 17x "paper profit").
     *  3. The quantity is sized so that your Buy Order AND your Sell Offer should fill in
     *     about TARGET_FILL_MIN minutes, taking competing orders into account.
     *  4. Among the items that pass, the winner has the best net profit per minute
     *     of the whole buy+sell cycle (slightly reduced for wide gaps).
     */
    private static Pick analyze(JsonObject products, double budget) {
        Pick best = null;
        double bestScore = 0;
        for (Map.Entry<String, com.google.gson.JsonElement> entry : products.entrySet()) {
            try {
                JsonObject p = entry.getValue().getAsJsonObject();
                JsonArray sellSummary = p.getAsJsonArray("sell_summary"); // Buy Orders (highest first)
                JsonArray buySummary = p.getAsJsonArray("buy_summary");   // Sell Offers (lowest first)
                if (sellSummary.size() == 0 || buySummary.size() == 0) {
                    continue;
                }
                JsonObject q = p.getAsJsonObject("quick_status");

                double topBuyOrder = sellSummary.get(0).getAsJsonObject().get("pricePerUnit").getAsDouble();
                double lowSellOffer = buySummary.get(0).getAsJsonObject().get("pricePerUnit").getAsDouble();
                if (topBuyOrder <= 0 || lowSellOffer <= 0) {
                    continue;
                }

                double buy = round1(topBuyOrder + 0.1);
                double sell = round1(lowSellOffer - 0.1);
                if (sell <= buy) {
                    continue;
                }
                double netUnit = sell * (1.0 - FEE) - buy;
                if (netUnit <= 0) {
                    continue;
                }

                // 1) Real demand on both sides.
                double flowBuyDay = q.get("sellMovingWeek").getAsDouble() / 7.0;  // fills your Buy Order
                double flowSellDay = q.get("buyMovingWeek").getAsDouble() / 7.0;  // fills your Sell Offer
                if (flowBuyDay < MIN_DAILY || flowSellDay < MIN_DAILY) {
                    continue;
                }
                double buyOrdersOpen = num(q, "buyOrders");
                double sellOrdersOpen = num(q, "sellOrders");
                if ((buyOrdersOpen >= 0 && buyOrdersOpen < MIN_ORDERS)
                        || (sellOrdersOpen >= 0 && sellOrdersOpen < MIN_ORDERS)) {
                    continue;
                }

                // 2) Realistic price gap.
                double ratio = sell / buy;
                if (ratio > MAX_SPREAD_RATIO) {
                    continue;
                }

                // 3) Size the trade so both sides fill fast.
                double compBuy = levelOrders(sellSummary, TOP_LEVELS);
                double compSell = levelOrders(buySummary, TOP_LEVELS);
                double captureBuy = capture(compBuy);
                double captureSell = capture(compSell);
                double rateBuyH = flowBuyDay / 24.0 * captureBuy;   // units/hour you can expect to buy
                double rateSellH = flowSellDay / 24.0 * captureSell; // units/hour you can expect to sell

                double maxByBuy = Math.floor(rateBuyH * TARGET_FILL_MIN / 60.0);
                double maxBySell = Math.floor(rateSellH * TARGET_FILL_MIN / 60.0);
                long qty = (long) Math.floor(budget / buy);
                qty = Math.min(qty, (long) maxByBuy);
                qty = Math.min(qty, (long) maxBySell);
                if (qty < 1) {
                    continue;
                }

                double cost = buy * qty;
                double gross = sell * qty;
                double fees = gross * FEE;
                double net = gross - cost - fees;
                double roi = net / cost * 100.0;
                if (net < MIN_NET || roi < MIN_ROI || cost > budget) {
                    continue;
                }

                double waitBuyH = qty / rateBuyH;
                double waitSellH = qty / rateSellH;
                double cycleMin = (waitBuyH + waitSellH) * 60.0;
                if (cycleMin > MAX_CYCLE_MIN) {
                    continue;
                }

                // 4) Score = net profit per hour of the whole cycle, reduced for wide gaps.
                double perHour = net / Math.max(0.05, waitBuyH + waitSellH);
                double penalty = ratio > SPREAD_PENALTY_START
                        ? 1.0 / (1.0 + (ratio - SPREAD_PENALTY_START) * 6.0)
                        : 1.0;
                double score = perHour * penalty;

                if (best == null || score > bestScore) {
                    Pick pick = new Pick();
                    pick.name = pretty(entry.getKey());
                    pick.instantBuy = lowSellOffer;
                    pick.buyOrder = buy;
                    pick.sellOffer = sell;
                    pick.qty = (int) qty;
                    pick.cost = cost;
                    pick.gross = gross;
                    pick.fees = fees;
                    pick.net = net;
                    pick.roi = roi;
                    pick.perHour = perHour;
                    pick.waitBuyH = waitBuyH;
                    pick.waitSellH = waitSellH;
                    pick.flowBuyDay = flowBuyDay;
                    pick.flowSellDay = flowSellDay;
                    pick.compBuy = compBuy;
                    pick.compSell = compSell;
                    pick.spreadPct = (ratio - 1.0) * 100.0;
                    best = pick;
                    bestScore = score;
                }
            } catch (Exception ignored) {
                // skip products with unexpected data
            }
        }
        return best;
    }

    /** Share of the market flow you can expect when other people compete at the top price levels. */
    private static double capture(double competingOrders) {
        double c = 1.0 / (1.0 + competingOrders * 0.1);
        return Math.max(0.15, Math.min(0.6, c));
    }

    /** Number of orders in the first few price levels of an order book. */
    private static double levelOrders(JsonArray book, int levels) {
        double sum = 0;
        int n = Math.min(levels, book.size());
        for (int i = 0; i < n; i++) {
            JsonObject lvl = book.get(i).getAsJsonObject();
            sum += lvl.has("orders") ? lvl.get("orders").getAsDouble() : 1.0;
        }
        return sum;
    }

    /** Reads a number from a JSON object, or -1 if it is missing. */
    private static double num(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsDouble() : -1.0;
    }

    // ---------------------------------------------------------------------
    // Output. The 8 main values are green (\u00a7a) and come first; everything else is white (\u00a7f) and comes after.
    // ---------------------------------------------------------------------

    private static String green(String s) {
        return "\u00a7a" + s;
    }

    private static String white(String s) {
        return "\u00a7f" + s;
    }

    private static void show(FabricClientCommandSource src, double budget, Pick p) {
        // --- Green block: the 8 main values, in this exact order, one after another ---
        msg(src, green("Item: " + p.name));
        msg(src, green("Buy Order price: " + d(p.buyOrder)));
        msg(src, green("Quantity: " + n(p.qty)));
        msg(src, green("Total cost: " + n(p.cost)));
        msg(src, green("Sell Offer price: " + d(p.sellOffer)));
        msg(src, green("Gross sales: " + n(p.gross)));
        msg(src, green("Estimated net profit: " + n(p.net)));
        msg(src, green("ROI: " + String.format("%.2f", p.roi) + "%"));

        // --- White block: details, after the green block ---
        msg(src, white("--- Details ---"));
        msg(src, white("Budget: " + n(budget)));
        msg(src, white("Remaining budget: " + n(budget - p.cost)));
        msg(src, white("Instant buy price now: " + d(p.instantBuy) + " (not recommended)"));
        msg(src, white("Estimated fees (1.25%): " + n(p.fees)));
        msg(src, white("Estimated fill time: " + t(p.waitBuyH)));
        msg(src, white("Estimated sell time: " + t(p.waitSellH)));
        msg(src, white("--- Market activity ---"));
        msg(src, white("Instant-sold per day (fills Buy Orders): " + n(p.flowBuyDay)));
        msg(src, white("Instant-bought per day (fills Sell Offers): " + n(p.flowSellDay)));
        msg(src, white("Competing orders at top levels: buy " + n(p.compBuy) + " / sell " + n(p.compSell)));
        msg(src, white("Price gap (Sell vs Buy Order): " + String.format("%.1f", p.spreadPct) + "%"));
        msg(src, white("Estimate only. Prices change; profit is not guaranteed."));
    }

    private static void msg(FabricClientCommandSource src, String text) {
        Minecraft.getInstance().execute(() -> src.sendFeedback(Component.literal(text)));
    }

    private static double parseBudget(String s) {
        Matcher m = Pattern.compile("^(\\d+(?:\\.\\d+)?)([kmb]?)$", Pattern.CASE_INSENSITIVE).matcher(s.trim());
        if (!m.matches()) {
            return -1.0;
        }
        double v = Double.parseDouble(m.group(1));
        switch (m.group(2).toLowerCase()) {
            case "k":
                return v * 1000.0;
            case "m":
                return v * 1_000_000.0;
            case "b":
                return v * 1_000_000_000.0;
            default:
                return v;
        }
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static String n(double v) {
        return String.format("%,.0f", v);
    }

    private static String d(double v) {
        return String.format("%,.1f", v);
    }

    private static String t(double hours) {
        double minutes = hours * 60.0;
        if (minutes < 1.0) {
            return "under 1 minute";
        }
        if (minutes < 60.0) {
            return "about " + Math.round(minutes) + " min";
        }
        return "about " + String.format("%.1f", hours) + " hours";
    }

    private static String pretty(String id) {
        String[] parts = id.toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1)).append(' ');
        }
        return sb.toString().trim();
    }
}
