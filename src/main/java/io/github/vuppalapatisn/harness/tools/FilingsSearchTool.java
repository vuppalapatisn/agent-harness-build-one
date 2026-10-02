package io.github.vuppalapatisn.harness.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.vuppalapatisn.harness.config.HarnessProperties;
import io.github.vuppalapatisn.harness.model.ModelGateway.ToolSpec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Retrieval over quarterly filings (the article's RAG piece, top-k = 4 by default).
 *
 * <p>Uses a lightweight lexical scorer over {@code classpath:filings/*.md} so it runs anywhere;
 * swap {@link #score} for a vector store (pgvector, OpenSearch...) in production.
 */
@Component
public class FilingsSearchTool implements AgentTool {

    private static final Set<String> STOP_WORDS = Set.of(
            "the", "a", "an", "of", "and", "or", "in", "on", "for", "to", "is", "was", "what", "how", "did", "with", "by");

    private record Chunk(String source, String text, Set<String> terms) {}

    private final List<Chunk> chunks = new ArrayList<>();
    private final int topK;
    private final ToolSpec spec;

    public FilingsSearchTool(HarnessProperties properties, ObjectMapper mapper) throws IOException {
        this.topK = properties.getTools().getRetrievalTopK();
        this.spec = new ToolSpec(
                "search_filings",
                "Search the company's quarterly financial filings and return the most relevant passages "
                        + "with their source document. Use this before answering any question about revenue, "
                        + "margins, guidance, risks or other reported figures.",
                mapper.readTree("""
                        {"type":"object",
                         "properties":{"query":{"type":"string","description":"Natural-language search query"}},
                         "required":["query"]}
                        """));
        Resource[] resources = new PathMatchingResourcePatternResolver().getResources("classpath*:filings/*.md");
        for (Resource resource : resources) {
            try (InputStream in = resource.getInputStream()) {
                String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                for (String paragraph : content.split("\\n\\s*\\n")) {
                    if (!paragraph.isBlank()) {
                        chunks.add(new Chunk(resource.getFilename(), paragraph.strip(), terms(paragraph)));
                    }
                }
            }
        }
    }

    @Override
    public ToolSpec spec() {
        return spec;
    }

    @Override
    public String execute(JsonNode input) {
        String query = input.path("query").asText("");
        if (query.isBlank()) {
            throw new IllegalArgumentException("query is required");
        }
        Set<String> queryTerms = terms(query);
        List<Chunk> hits = chunks.stream()
                .filter(c -> score(queryTerms, c) > 0)
                .sorted(Comparator.comparingDouble((Chunk c) -> score(queryTerms, c)).reversed())
                .limit(topK)
                .toList();
        if (hits.isEmpty()) {
            return "No matching passages found in the filings.";
        }
        return hits.stream()
                .map(c -> "[source: " + c.source() + "]\n" + c.text())
                .collect(Collectors.joining("\n---\n"));
    }

    static double score(Set<String> queryTerms, Chunk chunk) {
        long overlap = queryTerms.stream().filter(chunk.terms()::contains).count();
        return overlap == 0 ? 0 : overlap / Math.sqrt(chunk.terms().size());
    }

    private static Set<String> terms(String text) {
        return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^a-z0-9%$.]+"))
                .map(t -> t.replaceAll("^[.]+|[.]+$", ""))
                .filter(t -> t.length() > 1 && !STOP_WORDS.contains(t))
                .collect(Collectors.toSet());
    }
}
