package com.bs.narrative.service;

import com.bs.narrative.exception.BusinessException;
import com.bs.narrative.exception.ErrorCode;
import com.bs.narrative.llm.Pass2Service;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM 输出 hidden_summary 的 Schema 校验器（毕设级：stage 边界完成对象化与字段映射）。
 *
 * 校验维度：
 *   1) 字段存在性（locationName / plotSummary 必填）
 *   2) 字段类型（coordX/coordY 为数字，presentGods/keyEvents 为数组）
 *   3) 字段值合法性（坐标范围、字符串非空、数组非空）
 *
 * 异常分类：
 *   - FIELD_MISSING：必填字段缺失
 *   - TYPE_ERROR：字段类型错误
 *   - SCHEMA_ERROR：整体 schema 不合法
 *
 * 设计原则（参考项目经验）：pipeline 中某阶段产物影响后续决策时，
 * 必须在 stage 边界完成对象化与字段映射，避免下游服务猜测上游结构。
 */
@Component
public class StorySummaryValidator {

    private static final Logger log = LoggerFactory.getLogger(StorySummaryValidator.class);

    /** 坐标合法范围（防止 LLM 输出天文数字导致索引失效）。 */
    private static final int COORD_MIN = -10000;
    private static final int COORD_MAX = 10000;

    /** locationName 最大长度。 */
    private static final int LOCATION_NAME_MAX = 64;

    /**
     * 校验 LLM 输出的 hidden_summary JsonNode。
     *
     * @param hsNode LLM 输出的 hidden_summary 节点
     * @return 校验通过的 HiddenSummary 对象
     * @throws BusinessException 字段缺失 / 类型错误 / 值非法
     */
    public Pass2Service.HiddenSummary validate(JsonNode hsNode) {
        if (hsNode == null || hsNode.isMissingNode() || hsNode.isNull()) {
            throw new BusinessException(ErrorCode.LLM_PARSE_FAILED,
                    "hidden_summary 节点缺失");
        }

        // 1) 必填字段：locationName
        JsonNode locationNode = hsNode.path("locationName");
        if (locationNode.isMissingNode() || !locationNode.isTextual()
                || locationNode.asText().isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_MISSING,
                    "hidden_summary.locationName 缺失或为空");
        }
        String locationName = locationNode.asText().trim();
        if (locationName.length() > LOCATION_NAME_MAX) {
            throw new BusinessException(ErrorCode.PARAM_RANGE_INVALID,
                    "hidden_summary.locationName 超长（>" + LOCATION_NAME_MAX + "）");
        }

        // 2) 必填字段：plotSummary
        JsonNode plotNode = hsNode.path("plotSummary");
        if (plotNode.isMissingNode() || !plotNode.isTextual()
                || plotNode.asText().isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_MISSING,
                    "hidden_summary.plotSummary 缺失或为空");
        }
        String plotSummary = plotNode.asText().trim();

        // 3) 坐标：必须是数字，且在合法范围
        Integer coordX = parseCoord(hsNode, "coordX");
        Integer coordY = parseCoord(hsNode, "coordY");

        // 4) storyTime：可选，文本类型
        String storyTime = null;
        JsonNode timeNode = hsNode.path("storyTime");
        if (timeNode.isTextual() && !timeNode.asText().isBlank()) {
            storyTime = timeNode.asText().trim();
        }

        // 5) presentGods：必须是数组（可空）
        List<String> presentGods = parseStringArray(hsNode, "presentGods");

        // 6) keyEvents：必须是数组（可空）
        List<String> keyEvents = parseStringArray(hsNode, "keyEvents");

        // 7) stateChanges：可选，文本
        String stateChanges = "无";
        JsonNode stateNode = hsNode.path("stateChanges");
        if (stateNode.isTextual() && !stateNode.asText().isBlank()) {
            stateChanges = stateNode.asText().trim();
        }

        return new Pass2Service.HiddenSummary(
                locationName, coordX, coordY, storyTime,
                plotSummary, presentGods, keyEvents, stateChanges);
    }

    private Integer parseCoord(JsonNode root, String field) {
        JsonNode node = root.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return 0; // 缺省坐标 0
        }
        if (!node.isNumber()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "hidden_summary." + field + " 必须是数字");
        }
        int val = node.asInt();
        if (val < COORD_MIN || val > COORD_MAX) {
            throw new BusinessException(ErrorCode.PARAM_RANGE_INVALID,
                    "hidden_summary." + field + " 越界（" + COORD_MIN + "~" + COORD_MAX + "）");
        }
        return val;
    }

    private List<String> parseStringArray(JsonNode root, String field) {
        JsonNode node = root.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return new ArrayList<>();
        }
        if (!node.isArray()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "hidden_summary." + field + " 必须是数组");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : node) {
            if (item.isTextual() && !item.asText().isBlank()) {
                result.add(item.asText().trim());
            }
        }
        return result;
    }
}
