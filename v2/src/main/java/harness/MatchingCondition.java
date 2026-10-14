package harness;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rule for the rule-based model: a tool matches a question when the question has
 * one of the keywords AND an id of the shape the tool needs. Returns that id.
 */
public record MatchingCondition(List<String> keywords, Pattern idPattern) {

    public Optional<String> match(String question) {
        String lower = question.toLowerCase();
        boolean hasKeyword = false;
        for (String keyword : keywords) {
            if (lower.contains(keyword)) {
                hasKeyword = true;
            }
        }
        if (!hasKeyword) {
            return Optional.empty();
        }
        Matcher matcher = idPattern.matcher(question);
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.of(matcher.group().toUpperCase());
    }
}
