package com.bs.narrative.exception;

import lombok.Getter;

/**
 * 6 位错误码体系（毕设级：便于论文截图与前端统一处理）。
 *
 * 段位划分：
 *   10xxxx  参数校验
 *   20xxxx  资源/数据
 *   30xxxx  LLM / 外部服务
 *   40xxxx  业务规则 / 门禁
 *   50xxxx  系统 / 未知
 */
@Getter
public enum ErrorCode {

    // —— 10xxxx 参数校验 ——
    PARAM_MISSING(100001, "缺少必填参数"),
    PARAM_INVALID(100002, "参数格式非法"),
    PARAM_RANGE_INVALID(100003, "参数取值越界"),

    // —— 20xxxx 资源/数据 ——
    SCENE_NOT_FOUND(200001, "场景不存在"),
    GOD_NOT_FOUND(200002, "神明不存在"),
    PLAYER_NOT_FOUND(200003, "玩家不存在"),
    RELATIONSHIP_NOT_FOUND(200004, "关系数据不存在"),
    DATA_CONFLICT(200009, "数据冲突（坐标重复/唯一约束冲突）"),
    OPERATION_NOT_FOUND(200010, "操作尚未登记"),

    // —— 30xxxx LLM / 外部服务 ——
    LLM_API_FAILED(300001, "LLM 调用失败"),
    LLM_PARSE_FAILED(300002, "LLM 输出解析失败"),
    LLM_TIMEOUT(300003, "LLM 调用超时"),
    LLM_KEY_NOT_CONFIGURED(300004, "LLM API Key 未配置"),

    // —— 40xxxx 业务规则 / 门禁 ——
    INTENT_VALIDATION_FAILED(400001, "意图校验失败"),
    MOVE_EXIT_NOT_FOUND(400101, "当前场景无此出口"),
    MOVE_GATE_ITEM_REQUIRED(400102, "通过此出口需持有指定物品"),
    MOVE_GATE_OBJECTIVE_REQUIRED(400103, "通过此出口需先完成指定目标"),
    MOVE_COORD_NOT_FOUND(400104, "该坐标在本会话中无已记录场景"),
    MOVE_SCENE_MISMATCH(400105, "玩家当前所在场景与出口起点不匹配（可能已被其他请求移动）"),
    COORD_CONFLICT(400201, "坐标与已存在场景冲突"),
    CREATION_STATE_CONFLICT(400301, "角色创建步骤与当前状态不符"),
    DEATH_NOT_REGISTERED(400302, "角色尚未登记死亡"),

    // —— 50xxxx 系统 / 未知 ——
    INTERNAL_ERROR(500000, "系统内部错误"),
    AUTH_REQUIRED(600001,"请先登录"),
    AUTH_INVALID_CREDENTIALS(600002,"账号或密码错误"),
    AUTH_FORBIDDEN(600003,"无权访问");

    private final int code;
    private final String defaultMessage;

    ErrorCode(int code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }
}
