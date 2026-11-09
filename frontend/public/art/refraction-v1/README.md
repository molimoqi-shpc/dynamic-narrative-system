# 折光叙事人物素材 v1 · 2026-10-25

本批由内置 ImageGen 生成。人物为 1024×1536 RGBA 透明 PNG，背景为同尺寸 RGB PNG。项目保存原始输出，未使用外部修图脚本。

| 文件 | 可用类型 |
| --- | --- |
| western-elf-female.png | 西方、年轻/成年女性精灵 |
| western-knight-male.png | 西方、年轻/成年男性人类骑士 |
| western-noble-female.png | 西方、年轻/成年女性人类礼服贵族 |
| western-warrior-female.png | 西方、年轻/成年女性人类战士 |
| western-cleric-male.png | 西方、老年男性人类教宗/高阶教士 |
| western-dragon-rider-male.png | 西方、年轻/成年男性人类龙骑士 |
| eastern-woman.png | 东方成年女性；替换旧 woman-v2 |
| eastern-man.png | 东方成年男性；替换旧 man |
| stage-western.png | 西方幻想石拱大厅背景意象，无人物 |

美术方向：写实游戏角色、手绘材质、克制的边缘光、完整头部和手部、透明底、三分之二身构图。东方成年女性采用灰蓝汉服与朱红内衫，成年男性采用普通行旅服饰；西方职业通过衣装轮廓区分，避免用同一女性立绘代表全部职业。

匹配入口：src/utils/characterPortrait.js。只用公开外貌资料与存档世界文化，不以姓名或隐秘心理猜配。不匹配的性别、年龄、种族、职业显示未知形象，不能借用其他类型。素材是类型代表图，不承诺每个 NPC 有独一无二的肖像。

东方少女、老人、儿童暂保留 paper-theatre-v1 的旧资产；现代、科幻、更多职业性别组合尚未覆盖。不可将本目录描述为全人口素材库。

动效由 CharacterStage.vue 与 refraction.css 实现：实像轻摆、独立剪影漂移、指针视差、斜向遮罩切换。静态素材未进行骨骼拆分，因此没有眼睛眨动、口型或肢体关节运动。
