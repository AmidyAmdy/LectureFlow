package ru.filatov.lectureflow.search;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ru.filatov.lectureflow.web.InvalidRequestException;

@RestController
public class SearchController {

    private static final int MAX_RESULT_WINDOW = 10_000;

    private final SearchService searchService;
    private final ReindexService reindexService;

    public SearchController(SearchService searchService, ReindexService reindexService) {
        this.searchService = searchService;
        this.reindexService = reindexService;
    }

    @GetMapping(value = "/search", produces = "application/json")
    public List<SearchResult> search(
            @RequestParam("q")
            @NotBlank(message = "Введите текст для поиска")
            @Size(max = 200, message = "Запрос должен быть не длиннее 200 символов") String query) {
        return searchService.search(query.trim(), 0, 10).items();
    }

    @GetMapping(value = "/search/segments", produces = "application/json")
    public SearchPage searchSegments(
            @RequestParam("q")
            @NotBlank(message = "Введите текст для поиска")
            @Size(max = 200, message = "Запрос должен быть не длиннее 200 символов") String query,
            @RequestParam(value = "page", defaultValue = "0")
            @Min(value = 0, message = "page не может быть отрицательным") int page,
            @RequestParam(value = "size", defaultValue = "20")
            @Min(value = 1, message = "size должен быть от 1 до 50")
            @Max(value = 50, message = "size должен быть от 1 до 50") int size) {
        if ((long) (page + 1) * size > MAX_RESULT_WINDOW) {
            throw new InvalidRequestException("Слишком далёкая страница: уточните запрос");
        }
        return searchService.search(query.trim(), page, size);
    }

    @PostMapping("/search/reindex")
    public ReindexResult reindex() {
        return reindexService.reindexAll();
    }
}
