package com.dealit.dealit.domain.search.service;

import com.dealit.dealit.domain.search.config.OpenSearchProperties;
import com.dealit.dealit.domain.search.document.SearchDocument;
import com.dealit.dealit.domain.search.dto.SearchItemResponse;
import com.dealit.dealit.domain.search.dto.SearchListResponse;
import com.dealit.dealit.domain.search.dto.SearchResultType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class OpenSearchClient {

	private static final String NDJSON_MEDIA_TYPE = "application/x-ndjson";

	private final OpenSearchProperties properties;
	private final ObjectMapper objectMapper;
	private final RestClient restClient;

	public OpenSearchClient(OpenSearchProperties properties, ObjectMapper objectMapper) {
		this.properties = properties;
		this.objectMapper = objectMapper;
		this.restClient = RestClient.builder()
			.baseUrl(properties.getUri())
			.build();
	}

	public String indexName() {
		return properties.getIndexName();
	}

	public boolean isEnabled() {
		return properties.isEnabled();
	}

	public SearchListResponse search(String keyword, SearchResultType type, Long categoryId, int page, int size) {
		ensureEnabled();
		Map<String, Object> request = Map.of(
			"from", page * size,
			"size", size,
			"sort", List.of(Map.of("createdAt", Map.of("order", "desc"))),
			"query", buildSearchQuery(keyword, type, categoryId)
		);

		String response = restClient.post()
			.uri("/{index}/_search", properties.getIndexName())
			.contentType(MediaType.APPLICATION_JSON)
			.body(request)
			.retrieve()
			.body(String.class);

		JsonNode hits = readTree(response).path("hits");
		long totalElements = hits.path("total").path("value").asLong(0L);
		List<SearchItemResponse> content = new ArrayList<>();
		for (JsonNode hit : hits.path("hits")) {
			content.add(objectMapper.convertValue(hit.path("_source"), SearchItemResponse.class));
		}
		return new SearchListResponse(keyword, type, categoryId, content, page, size, totalElements, (long) (page + 1) * size < totalElements);
	}

	private Map<String, Object> buildSearchQuery(String keyword, SearchResultType type, Long categoryId) {
		boolean hasKeyword = keyword != null && !keyword.isBlank();
		boolean hasCategory = categoryId != null;
		if (hasKeyword) {
			return Map.of(
				"bool", Map.of(
					"must", List.of(multiMatchQuery(keyword)),
					"filter", buildFilters(type, categoryId)
				)
			);
		}
		return Map.of("bool", Map.of("filter", buildFilters(type, categoryId)));
	}

	private Map<String, Object> multiMatchQuery(String keyword) {
		return Map.of(
			"multi_match", Map.of(
				"query", keyword,
				"fields", List.of("name^3", "description", "categoryNames^2"),
				"fuzziness", "AUTO"
			)
		);
	}

	private Map<String, Object> categoryFilter(Long categoryId) {
		return Map.of("term", Map.of("categoryPathIds", categoryId));
	}

	private Map<String, Object> typeFilter(SearchResultType type) {
		return Map.of("term", Map.of("type", type.name()));
	}

	private List<Map<String, Object>> buildFilters(SearchResultType type, Long categoryId) {
		List<Map<String, Object>> filters = new ArrayList<>();
		if (type != null) {
			filters.add(typeFilter(type));
		}
		if (categoryId != null) {
			filters.add(categoryFilter(categoryId));
		}
		return filters;
	}

	public void recreateIndex() {
		ensureEnabled();
		deleteIndexIfExists();
		createIndexIfNeeded();
	}

	public int bulkIndex(List<SearchDocument> documents) {
		ensureEnabled();
		if (documents.isEmpty()) {
			return 0;
		}
		StringBuilder body = new StringBuilder();
		for (SearchDocument document : documents) {
			body.append(toJson(Map.of("index", Map.of("_index", properties.getIndexName(), "_id", document.id()))))
				.append('\n');
			body.append(toJson(document)).append('\n');
		}
		String response = restClient.post()
			.uri("/_bulk")
			.contentType(MediaType.parseMediaType(NDJSON_MEDIA_TYPE))
			.body(toUtf8Bytes(body.toString()))
			.retrieve()
			.body(String.class);
		validateBulkResponse(response);
		return documents.size();
	}

	public void index(SearchDocument document) {
		ensureEnabled();
		createIndexIfNeeded();
		restClient.put()
			.uri("/{index}/_doc/{id}", properties.getIndexName(), document.id())
			.contentType(MediaType.APPLICATION_JSON)
			.body(toUtf8Bytes(toJson(document)))
			.retrieve()
			.toBodilessEntity();
	}

	public void indexIfVersionNotOlder(SearchDocument document) {
		ensureEnabled();
		createIndexIfNeeded();
		Map<String, Object> body = Map.of(
			"scripted_upsert", true,
			"script", Map.of(
				"lang", "painless",
				"source", """
					if (ctx._source.searchVersion == null || ctx._source.searchVersion <= params.document.searchVersion) {
					  ctx._source.clear();
					  ctx._source.putAll(params.document);
					} else {
					  ctx.op = 'noop';
					}
					""",
				"params", Map.of("document", document)
			),
			"upsert", document
		);
		restClient.post()
			.uri("/{index}/_update/{id}", properties.getIndexName(), document.id())
			.contentType(MediaType.APPLICATION_JSON)
			.body(toUtf8Bytes(toJson(body)))
			.retrieve()
			.toBodilessEntity();
	}

	public void delete(String documentId) {
		ensureEnabled();
		createIndexIfNeeded();
		restClient.delete()
			.uri("/{index}/_doc/{id}", properties.getIndexName(), documentId)
			.exchange((request, response) -> null);
	}

	private void createIndexIfNeeded() {
		Boolean exists = restClient.head()
			.uri("/{index}", properties.getIndexName())
			.exchange((request, response) -> response.getStatusCode().is2xxSuccessful());
		if (Boolean.TRUE.equals(exists)) {
			return;
		}
		Map<String, Object> body = Map.of(
			"settings", Map.of(
				"index.max_ngram_diff", 18,
				"analysis", Map.of(
					"tokenizer", Map.of(
						"dealit_ngram_tokenizer", Map.of(
							"type", "ngram",
							"min_gram", 2,
							"max_gram", 20,
							"token_chars", List.of("letter", "digit")
						)
					),
					"analyzer", Map.of(
						"dealit_text_analyzer", Map.of(
							"type", "custom",
							"tokenizer", "standard",
							"filter", List.of("lowercase")
						),
						"dealit_name_ngram_analyzer", Map.of(
							"type", "custom",
							"tokenizer", "dealit_ngram_tokenizer",
							"filter", List.of("lowercase")
						)
					)
				)
			),
			"mappings", Map.of(
				"properties", Map.of(
					"name", Map.of(
						"type", "text",
						"analyzer", "dealit_name_ngram_analyzer",
						"search_analyzer", "dealit_text_analyzer"
					),
					"description", Map.of("type", "text", "analyzer", "dealit_text_analyzer"),
					"categoryNames", Map.of("type", "text", "analyzer", "dealit_text_analyzer"),
					"categoryId", Map.of("type", "long"),
					"categoryPathIds", Map.of("type", "long"),
					"type", Map.of("type", "keyword"),
					"productStatus", Map.of("type", "keyword"),
					"auctionStatus", Map.of("type", "keyword"),
					"searchVersion", Map.of("type", "long"),
					"createdAt", Map.of("type", "date")
				)
			)
		);
		restClient.put()
			.uri("/{index}", properties.getIndexName())
			.contentType(MediaType.APPLICATION_JSON)
			.body(body)
			.retrieve()
			.toBodilessEntity();
	}

	private void deleteIndexIfExists() {
		restClient.delete()
			.uri("/{index}", properties.getIndexName())
			.exchange((request, response) -> null);
	}

	private String toJson(Object value) {
		try {
			return objectMapper.copy()
				.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
				.writeValueAsString(value);
		} catch (Exception exception) {
			throw new IllegalStateException("Failed to serialize OpenSearch request.", exception);
		}
	}

	private byte[] toUtf8Bytes(String value) {
		return value.getBytes(StandardCharsets.UTF_8);
	}

	private void validateBulkResponse(String value) {
		JsonNode response = readTree(value);
		if (!response.path("errors").asBoolean(false)) {
			return;
		}
		JsonNode firstError = response.path("items").path(0).path("index").path("error");
		throw new IllegalStateException("OpenSearch bulk indexing failed: " + firstError);
	}

	private JsonNode readTree(String value) {
		try {
			return objectMapper.readTree(value);
		} catch (Exception exception) {
			throw new IllegalStateException("Failed to parse OpenSearch response.", exception);
		}
	}

	private void ensureEnabled() {
		if (!properties.isEnabled()) {
			throw new IllegalStateException("OpenSearch is disabled.");
		}
	}
}
