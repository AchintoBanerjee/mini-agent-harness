package harness;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** The three example tools. Data is hard-coded; production would call real services. */
public class SampleTools {

    private static final Map<String, String> ORDERS = Map.of(
            "ORD-1", "SHIPPED, shipment SHP-1",
            "ORD-2", "PROCESSING",
            "ORD-3", "DELIVERED, shipment SHP-3");

    private static final Map<String, String> SHIPMENTS = Map.of(
            "SHP-1", "IN TRANSIT, arriving 2026-10-16",
            "SHP-3", "DELIVERED on 2026-10-10");

    private static final Map<String, Integer> STOCK = Map.of(
            "PRD-1", 42,
            "PRD-2", 0);

    public static ToolRegistry registry() {
        ToolRegistry registry = new ToolRegistry();
        for (Tool tool : all()) {
            registry.register(tool);
        }
        return registry;
    }

    public static List<Tool> all() {
        return List.of(orderStatus(), shippingStatus(), stockStatus());
    }

    public static Tool orderStatus() {
        return new Tool(
                "order_status",
                "Status of an order, by order id",
                "orderId",
                new MatchingCondition(List.of("order"), Pattern.compile("ORD-\\d+", Pattern.CASE_INSENSITIVE)),
                input -> {
                    String id = input.get("orderId");
                    String status = ORDERS.get(id);
                    return status == null ? "Order " + id + " not found" : "Order " + id + ": " + status;
                });
    }

    public static Tool shippingStatus() {
        return new Tool(
                "shipping_status",
                "Status of a shipment, by shipment id",
                "shipmentId",
                new MatchingCondition(List.of("shipment", "shipping", "tracking"),
                        Pattern.compile("SHP-\\d+", Pattern.CASE_INSENSITIVE)),
                input -> {
                    String id = input.get("shipmentId");
                    String status = SHIPMENTS.get(id);
                    return status == null ? "Shipment " + id + " not found" : "Shipment " + id + ": " + status;
                });
    }

    public static Tool stockStatus() {
        return new Tool(
                "stock_status",
                "Units in stock for a product, by product id",
                "productId",
                new MatchingCondition(List.of("stock", "inventory", "available"),
                        Pattern.compile("PRD-\\d+", Pattern.CASE_INSENSITIVE)),
                input -> {
                    String id = input.get("productId");
                    Integer units = STOCK.get(id);
                    if (units == null) {
                        return "Product " + id + " not found";
                    }
                    return units == 0 ? "Product " + id + ": OUT OF STOCK" : "Product " + id + ": " + units + " units in stock";
                });
    }
}
