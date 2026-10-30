package io.github.codejinxz.yilian.upload.validation;

import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 导入行解析器：把整份文件文本拆成逐行（切片设计 §4.3 语义）。
 *
 * <ul>
 *   <li>JSON：顶层是数组，或对象带 {@code items} 数组（对齐 mock 契约）；不是 JSON Lines；
 *   <li>CSV：首行表头 + 简单逗号分割；{@code chunks/blank/tags} 三列按 JSON 解析， 解析失败保留原文本交由业务校验器报错（对齐 mock
 *       parseCsvRows）。
 * </ul>
 *
 * <p>解析只做形状；字段级业务校验由 {@link ImportBusinessValidator} 负责。
 */
@Component
public final class ImportRowParser {

    /** 单批数据行上限（切片设计 Q7）。 */
    public static final int MAX_ROWS = 2000;

    private final ObjectMapper objectMapper;

    /**
     * @param objectMapper Jackson 映射器
     */
    @Autowired
    public ImportRowParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 解析结果行：行号、源 id 与规范化 JSON 文本。
     *
     * @param rowNo 数据行号，从 1 开始
     * @param externalKey 源 id，行内未提供时为 null
     * @param rawJson 该行的规范化 JSON 文本
     */
    public record ParsedRow(int rowNo, String externalKey, String rawJson) {}

    /**
     * 按格式解析文件文本。
     *
     * @param format 文件格式 {@code JSON} / {@code CSV}
     * @param text 全文件文本（UTF-8）
     * @return 数据行列表；超过 {@link #MAX_ROWS} 时抛业务异常
     * @throws BusinessException 形状不合法（JSON 数组缺失、CSV 缺表头等）时抛 40001
     */
    public List<ParsedRow> parse(String format, String text) {
        List<ParsedRow> rows =
                switch (format) {
                    case "JSON" -> parseJson(text);
                    case "CSV" -> parseCsv(text);
                    default -> throw new BusinessException(ApiCode.MALFORMED_REQUEST, "未知导入格式");
                };
        if (rows.size() > MAX_ROWS) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "单批最多 2000 行数据");
        }
        return rows;
    }

    /**
     * 解析 JSON：顶层数组或 {@code {items: []}}。
     *
     * @param text 全文件文本
     * @return 数据行列表
     */
    private List<ParsedRow> parseJson(String text) {
        JsonNode root = readTree(text);
        JsonNode items = root.isArray() ? root : root.path("items");
        if (!items.isArray()) {
            throw new BusinessException(ApiCode.MALFORMED_REQUEST, "JSON 必须是数组或 { items: [] }");
        }
        List<ParsedRow> rows = new ArrayList<>(items.size());
        int rowNo = 0;
        for (JsonNode item : items) {
            rowNo++;
            rows.add(toRow(rowNo, item));
        }
        return rows;
    }

    /**
     * 解析 CSV：首行表头，逗号分割；chunks/blank/tags 三列尝试 JSON 解析。
     *
     * @param text 全文件文本
     * @return 数据行列表
     */
    private List<ParsedRow> parseCsv(String text) {
        String normalized = text.replace("\r\n", "\n");
        List<String> lines = normalized.lines().filter(line -> !line.isBlank()).toList();
        if (lines.size() < 2) {
            throw new BusinessException(ApiCode.MALFORMED_REQUEST, "CSV 至少需要表头与一行数据");
        }
        List<String> headers =
                List.of(lines.getFirst().split(",", -1)).stream().map(String::trim).toList();
        List<ParsedRow> rows = new ArrayList<>(lines.size() - 1);
        int rowNo = 0;
        for (String line : lines.subList(1, lines.size())) {
            rowNo++;
            String[] cells = line.split(",", -1);
            Map<String, Object> row = new LinkedHashMap<>();
            for (int index = 0; index < headers.size(); index++) {
                String header = headers.get(index);
                String value = index < cells.length ? cells[index].trim() : "";
                if ("chunks".equals(header) || "blank".equals(header) || "tags".equals(header)) {
                    row.put(header, tryParseJson(value));
                } else {
                    row.put(header, value);
                }
            }
            rows.add(toRow(rowNo, objectMapper.valueToTree(row)));
        }
        return rows;
    }

    /**
     * 把行 JSON 规范化为文本并提取源 id。
     *
     * @param rowNo 行号
     * @param item 行节点
     * @return 结果行
     */
    private ParsedRow toRow(int rowNo, JsonNode item) {
        String externalKey =
                item.path("externalKey").isTextual() ? item.path("externalKey").asText() : null;
        return new ParsedRow(rowNo, externalKey, objectMapper.writeValueAsString(item));
    }

    /**
     * 解析 JSON 文本；失败按请求体格式错误处理。
     *
     * @param text JSON 文本
     * @return 解析结果
     */
    private JsonNode readTree(String text) {
        try {
            return objectMapper.readTree(text);
        } catch (RuntimeException exception) {
            throw new BusinessException(ApiCode.MALFORMED_REQUEST, "JSON 解析失败");
        }
    }

    /**
     * 尝试解析 JSON 文本；失败返回原字符串。
     *
     * @param value 单元格文本
     * @return 解析结果或原文本
     */
    private Object tryParseJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (RuntimeException exception) {
            return value;
        }
    }
}
