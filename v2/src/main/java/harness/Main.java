package harness;

import java.util.List;

/** Demo driver: asks the orchestrator a few sample questions and prints what happened. */
public class Main {

    public static void main(String[] args) {
        ToolRegistry registry = SampleTools.registry();
        Agent agent = new Agent("support-agent", List.of("order_status", "shipping_status", "stock_status"));
        Orchestrator orchestrator = new Orchestrator(
                agent, registry, new RuleBasedLLM(), new ToolCaller(registry), new RelaySummarizer());

        List<String> questions = List.of(
                "What is the status of order ORD-1?",
                "Where is my shipment SHP-1?",
                "Is product PRD-2 available?",
                "What is the status of order ORD-99?",
                "What is the weather today?");

        for (String question : questions) {
            String answer = orchestrator.run(question);
            System.out.println("Q: " + question);
            System.out.println("A: " + answer);
        }

        System.out.println();
        System.out.println("Trace of the first turn:");
        Turn first = orchestrator.conversation().turns().get(0);
        for (Span span : first.spans()) {
            System.out.println("  " + span.kind() + "  " + span.request() + "  ->  " + span.response());
        }
    }
}
