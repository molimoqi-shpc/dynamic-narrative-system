package com.bs.narrative.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * 场景连接图（拓扑图边）：描述场景之间的可移动关系。
 *
 * 【优化指导 6.2】从 (x,y) 直角坐标改为节点(Scene)+边(Exit)的图结构：
 *   - 玩家"往北走" → 查 direction='NORTH' 的边 → 跳转目标场景
 *   - 动态生成新场景时建立双向边（A 北通 B / B 南通 A）
 *   - 论文表述："基于动态图遍历的场景探索机制"
 *
 * 防坑规则3：带 session_id 字段，按会话隔离，避免跨周目串扰。
 * 旧模板级数据（无 session_id）仍兼容（session_id 为 null 时视为共享模板）。
 */
@Entity
@Table(name = "scene_connection",
       uniqueConstraints = @UniqueConstraint(name = "uk_session_from_dir",
                                             columnNames = {"session_id", "from_scene_id", "direction"}),
       indexes = {
               @Index(name = "idx_from", columnList = "from_scene_id"),
               @Index(name = "idx_session_from", columnList = "session_id, from_scene_id"),
               @Index(name = "idx_session_to", columnList = "session_id, to_scene_id")
       }
)
@Getter
@Setter
public class SceneConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "connection_id")
    private Long connectionId;

    /**
     * 防坑规则3：所属会话/周目ID。
     * null 表示跨 session 共享的预设模板（旧数据兼容）。
     */
    @Column(name = "session_id", length = 32)
    private String sessionId;

    @Column(name = "from_scene_id", length = 32, nullable = false)
    private String fromSceneId;

    @Column(name = "to_scene_id", length = 32, nullable = false)
    private String toSceneId;

    /**
     * 标准方向枚举：NORTH / SOUTH / EAST / WEST / UP / DOWN / OTHER。
     * 玩家"往北走" → 查 direction='NORTH'。
     * OTHER 表示非标准方向（如"穿过芦苇荡"），exit_name 存原始描述。
     */
    @Column(name = "direction", length = 16, nullable = false)
    private String direction;

    /** 出口名称（古风，如"北去彼岸"）。OTHER 时存原始文本。 */
    @Column(name = "exit_name", length = 50, nullable = false)
    private String exitName;

    /** 通过所需物品；为 null 表示无物品要求。 */
    @Column(name = "required_item", length = 64)
    private String requiredItem;

    /** 通过所需已完成目标ID；为 null 表示无目标要求。 */
    @Column(name = "required_objective", length = 64)
    private String requiredObjective;

    /** 路径描述（古风）。 */
    @Column(name = "note", length = 255)
    private String note;
}
