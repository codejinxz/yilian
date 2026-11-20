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
 * 导入行解析器：把整份文件文本拆成逐行。
 *
 * <ul>
 *   <li>JSON：顶层是数组，或对象带 {@code items} 数组；不是 JSON Lines；
 *   <li>CSV：首行表头 + RFC4180 子集解析（双引号包裹、{@code ""} 转义、引号内逗号/换行按字面量保留）， 数据行列数必须与表头一致；{@code
 *       chunks/blank/tags} 三列按 JSON 解析， 解析失败保留原文本交由业务校验器报错。
 * </ul>
 *
 * <p>解析只做形状；字段级业务校验由 {@link ImportBusinessValidator} 负责。
 */
@Component
public final class ImportRowParser {

    /** 单批数据行上限。 */
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
        // 输入归一化：Excel「CSV UTF-8」等工具导出会带 UTF-8 BOM，不剥离会让
        // 首列表头变成 \uFEFFzh、JSON 分支直接解析失败，错误文案指不到真因；CRLF/CR 统一为 \n
        // 供 CSV 记录扫描。
        String normalized = text;
        if (normalized.startsWith("\uFEFF")) {
            normalized = normalized.substring(1);
        }
        normalized = normalized.replace("\r\n", "\n").replace('\r', '\n');
        List<ParsedRow> rows =
                switch (format) {
                    case "JSON" -> parseJson(normalized);
                    case "CSV" -> parseCsv(normalized);
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
     * 解析 CSV：首行表头 + RFC4180 子集记录扫描；chunks/blank/tags 三列尝试 JSON 解析。
     *
     * <p>为什么自写而不用 commons-csv：本项目只需要「双引号 包裹 + {@code ""} 转义 + 引号内逗号/换行」的最小子集，数十行可完整覆盖并单测；朴素 {@code
     * split(",")} 会把含逗号的 chunks JSON 一列切成多列，CSV 导入实际不可用。
     *
     * @param text 全文件文本（BOM 与换行已归一）
     * @return 数据行列表
     * @throws BusinessException 缺表头/数据行列数与表头不一致/引号未闭合（40001）
     */
    private List<ParsedRow> parseCsv(String text) {
        List<List<String>> records = parseCsvRecords(text);
        if (records.size() < 2) {
            throw new BusinessException(ApiCode.MALFORMED_REQUEST, "CSV 至少需要表头与一行数据");
        }
        List<String> headers = records.getFirst().stream().map(String::trim).toList();
        List<ParsedRow> rows = new ArrayList<>(records.size() - 1);
        int rowNo = 0;
        for (List<String> record : records.subList(1, records.size())) {
            rowNo++;
            if (record.size() != headers.size()) {
                // 列数错位会把后续列整体右移、把 JSON 残片静默写进不可变快照，必须显式拒绝。
                throw new BusinessException(
                        ApiCode.MALFORMED_REQUEST,
                        "CSV 第 "
                                + rowNo
                                + " 行列数与表头不一致（表头 "
                                + headers.size()
                                + " 列，实际 "
                                + record.size()
                                + " 列）");
            }
            Map<String, Object> row = new LinkedHashMap<>();
            for (int index = 0; index < headers.size(); index++) {
                String header = headers.get(index);
                String value = record.get(index).trim();
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
     * RFC4180 子集记录扫描：逗号分隔、双引号包裹、{@code ""} 转义引号、引号内逗号与换行按 字面量保留；引号只在其位于字段起始处时开启，字段中间的裸引号按字面量。
     *
     * <p>按字符整段扫描而不是逐行 split：引号内的换行属于字段内容。空白记录（单字段且全空白， 如文件尾换行产生的空记录）跳过。
     *
     * @param text 全文件文本
     * @return 记录列表，每条为字段列表（不做 trim，交由调用方按列语义处理）
     * @throws BusinessException 引号未闭合（40001）
     */
    private static List<List<String>> parseCsvRecords(String text) {
        List<List<String>> records = new ArrayList<>();
        List<String> record = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        int length = text.length();
        for (int index = 0; index < length; index++) {
            char current = text.charAt(index);
            if (inQuotes) {
                if (current == '"') {
                    if (index + 1 < length && text.charAt(index + 1) == '"') {
                        field.append('"');
                        index++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(current);
                }
            } else if (current == ',') {
                record.add(field.toString());
                field.setLength(0);
            } else if (current == '\n') {
                record.add(field.toString());
                field.setLength(0);
                appendIfNotBlank(records, record);
            } else if (current == '"' && field.isEmpty()) {
                inQuotes = true;
            } else {
                field.append(current);
            }
        }
        if (inQuotes) {
            throw new BusinessException(ApiCode.MALFORMED_REQUEST, "CSV 引号未闭合");
        }
        if (field.length() > 0 || !record.isEmpty()) {
            record.add(field.toString());
            appendIfNotBlank(records, record);
        }
        return records;
    }

    /**
     * 追加一条非空白记录；单字段且全空白的记录（空行/文件尾换行）丢弃。
     *
     * <p>入参 {@code record} 会被复用（调用方继续向其累积字段），所以这里存入副本后清空原列表。
     *
     * @param records 汇总列表
     * @param record 待判定的记录
     */
    private static void appendIfNotBlank(List<List<String>> records, List<String> record) {
        boolean blank = record.size() == 1 && record.getFirst().isBlank();
        if (!blank) {
            records.add(List.copyOf(record));
        }
        record.clear();
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
