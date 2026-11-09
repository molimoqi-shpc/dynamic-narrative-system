package com.bs.narrative.service;

import com.bs.narrative.llm.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.stereotype.Service;
import java.util.*;

/** The main author copyedits unpublished prose and records that same text in one existing call. */
@Service
public class StoryArchiveService {
 private static final ObjectMapper JSON=new ObjectMapper();private final LLMProvider model;
 public StoryArchiveService(LLMProvider model){this.model=model;}
 /** MySQL JSON reorders object keys. Cache identity must survive a database round trip. */
 public static String source(JsonNode value){
  try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(canonical(value).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
  catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
 }
 private static JsonNode canonical(JsonNode value){
  if(value.isObject()){
   var out=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();var keys=new TreeSet<String>();value.fieldNames().forEachRemaining(keys::add);
   for(String key:keys)out.set(key,canonical(value.path(key)));return out;
  }
  if(value.isArray()){var out=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();for(var item:value)out.add(canonical(item));return out;}
  return value.deepCopy();
 }
 private static final String LEGACY_EDITORIAL_RULES="""
  你是主API的定稿归档阶段。你仍是唯一主创，在本次既有调用内完成最终正文、实际耗时及同一正文的记忆保存，不另开审稿调用。
  publicationMode=UNPUBLISHED_COPYEDIT时，finalStory是尚未发表的完整稿。先从before.latestPublishedPassage的实际末尾接起，通读整篇，确认每件事的起因、先后、人物所知和结尾结果；确有接续矛盾就修清，保留人物意愿、原有阶段结果和玩家的决策权，不增删参与者、不续写新事件。正常的文风和内容保留。
  先输出publicationReview:{passageAnchors:[{sourceQuote:"原稿连续短句",covers:"这段从何时到何时、概述还是具体场景",follows:"与前一段的实际先后关系"}],repairs:[{sourceQuote:"确有问题的原句",issue:"具体接续冲突或含混",resolution:"如何保留原有事件并修清"}]}。只取三五个真正跨阶段的锚点，包括概述转入具体场景和篇末余波；不逐句审计、不按天补流水账。这是供你完成同一稿的简短编辑依据，不是人物所知、未来剧情或世界书事实。必须据这些原句判断，不能以“整体连贯”代替阅读。发现经过已前进、后文又似倒回同一天时，修清概述覆盖范围或局部相对日序；只有全文确实无此问题才用unchanged。篇末细节也须与已过时日和已完成结果相容。
  最先给publication：正文确实修改时返回{story:"完整最终正文，包括全部保留段落",timeProgress:{scope:"THIS_PASSAGE",elapsedParts:[{amount:这份新稿的实际新增数量,unit:"YEAR/MONTH/WEEK/DAY/HOUR/SHICHEN/MINUTE/SECOND"}],to:"实际停点",basis:"修订稿新增跨度依据"}}。正文无需修改时只给{unchanged:true}，chapterOutcome和publicationFocus.authoredTimeFrame中的作者耗时随同一份正文保留，不再另估一次。返回相同全文也视为未修改，不另定一个时钟。只有旧稿确实缺少有效耗时时才补填原单位数量。
  时间从本篇真实起点向后推进。分清跨日概述覆盖的区间和随后展开的具体日子：写先过了几日，后面的具体日子须与这段间隔相容；若意在先概述头几天再展开其中一天，就自然说明概述范围，避免让读者误读成又过几天后倒回去。只改有歧义的衔接，不把正常跳日补成逐日流水账。普通日子可以省略，篇末总结总天数不在已有经过之外再加一次，回忆须与当前经过区分。起点不沿用更早章节的场景或日序标签。
  能勉强解释成合理不等于衔接已清楚。若一句概述可读作“从篇首累计”也可读作“从上一件事结束后又过”，而后面的具体场景只有前一种读法才成立，就在最终正文明确概述范围；不能在publicationReview里另换计时起点来替含混原句辩解。每个锚点从前一件事的实际末尾承接，包括已经发生的次日问答。只需自然改写衔接，不改变已有事件或把原文的日序强行解释成另一套。
  尤其读完篇末的实际余波：正文已经写“当夜”再写“第二天清早”，那次清早及后续傍晚已是新的一天，不能因为段末原标签仍写第七日就忽略过夜，也不能仅改成“这天”而仍沿用旧天数。将这种跨夜余波单独作为最后的阶段锚点；实际终点延后就按延后的最终正文给耗时，不迁就玩家原先想花几天。这里只是主创读懂原文先后，不要求程序解析日期。
  跨多个阶段时，publication.timeProgress.elapsedParts按少数主要阶段给互不重叠的新增时长，每项可附phase与sourceQuote说明对应原文；从上一篇停点开始依次覆盖到本篇真实结尾，程序累加各项。整段概述已过去的数日照实记这几日，后续出行、过夜与雨后余波按各自新增间隔追加，同日发生的事不重复加一天。不要用末日编号减首日编号充当总耗时，也不把篇首概述中的第一天当作尚未经过的零点。单一短场景仍只需一项；不是逐日任务表或世界模拟。basis由这些实际阶段说明，不另造一份总数。
  正文、最终耗时、个人经历、最终场景与下一篇交接必须说同一件事。timeProgress.basis只说明时间，不能夹带正文没写的额外验看、交付或对话；sourceQuotes逐字取最终正文。narrativeState.currentSituation只写真实末尾及仍有影响的状态，已结束事情不复活，不把背景提醒列成必须执行的新任务。角色经历只记本人确实亲历或获知的事，不用全局知情填满个人表。
  publicationMode=PUBLISHED_RECORD_ONLY时正文已发表，不返回publication，不修订正文或另定耗时，只保存给定原文与原时钟的真实结果。
  玩家预计的天数只是行动方向，不是最终稿实际时钟。每次已展开的过夜与次日都须在后续实际终点体现，不能改称“这天”便把已经发生的次日吞回前一日。未发表模式以publication.timeProgress与publication最终正文为同一来源；已发表模式保留原耗时。两种模式都只算本篇新增跨度，不跨篇相减各自第几日，不从旅程最早出发时刻重算。模糊跨度由主API按最终正文合理估计，服务器只换算单位，不裁定剧情。
  timeProgress优先返回elapsedParts:[{amount:非负数可为小数,unit:"YEAR/MONTH/WEEK/DAY/HOUR/SHICHEN/MINUTE/SECOND"}]，按本篇实际新增跨度用原单位给数量，由程序统一换算；SHICHEN一个时辰是7200秒，HOUR一小时是3600秒，DAY一日是86400秒。例一个半时辰给1.5 SHICHEN，绝不是1.5 HOUR；半日给0.5 DAY。复合跨度可分项相加，已过部分不再填。长期概述可用WEEK/MONTH/YEAR，通用存档折算一周7日、一月30日、一年365日；正文已明确特殊历法时用实际DAY数量，不为换算改写世界。elapsedDays与elapsedRemainderSeconds是旧格式兼容，可省略；elapsedParts有效时优先于旧秒数估计。
  顶层timeProgress可省略。未修改正文沿用其作者耗时；修订正文的耗时与publication.story绑定保存。归档自己的另一份时间估计不能覆盖未修改的作者正文；旧格式兼容已有存档。publicationMode=PUBLISHED_RECORD_ONLY及FINAL_MANUSCRIPT_RECORD_ONLY均只保留给定稿件与耗时。
  """;
 private static final String ARCHIVE_FIELDS="""
  输入没有提供写作时的候选结果表，因为候选可能与最后正文不一致。before是本段之前的记录，不能拿旧位置、旧关系卡或原先意愿覆盖正文已经发生的变化。人名与编号依给定索引对应。
  按正文发生顺序分别记录每个人实际看到、听到、亲历的内容。已离场者不得获得离场后留在原处的人的谈话、试验、物品交接等新知识；昏迷者也不得知道昏迷期间的事。silent不等于一直在场，旁白描写不等于每个人都见闻。无新经历的人可以省略；离场本身要更新本人处境与sceneOutcomes。
  通读finalStory到结尾，用同一份地点目录保存人物末态：endingPlaces:{"本次自取的临时key":{locationName:"稳定的粗粒度地点名称",opening:"该处实际情况"}}，endingCast:[{roleId:"给定人物编号",placeKey:"上述目录key",basis:"正文最后一次明确此人所在或离场的短句"}]。每个地点只定义一次，同场人物引用同一个key，不把动作、站姿写进地点名。程序按名称复用已建立地点，新地点分配编号；本次不写地点sceneId，不把唯一可见的旧编号套给尚未建档的新现场。before.locationIndex是已有名称参照，不是剧情允许抵达的清单。人物编号roleId仍用给定真实编号。
  endingCast包括玩家、实际参与的既有人物及给定编号的新人物，完整写出移动过的玩家、同行者、带路人及留在自己店铺的被访者最后所在。同场交谈者、仍同行者引用同一个placeKey；这是整段结束时的粗粒度快照，不是途中路线表，不追踪微动作，背景群众不填。只沿某方向离开、尚未到下一处时，目录写正文已表明的路段及实际情况，后续去向未知，不把离去者放回出发点，不编造抵达。例如主角与同伴正在回程、掌柜仍在店内，应是回程路和店内两个key，不能为了共用旧sceneId把所有人放回主角家。sceneOutcomes可省略，不重复输出另一套人物落点；旧格式仅由服务器兼容已保存回复。
  每个人的最后所在独立读到结尾。主角已死亡或沉水失踪、正文没有捞起遗体时，其最后所在仍是死亡处或最后出现的水域，不会跟着幸存者离场；幸存者去向单独记录。只有正文实际搬运了遗体才改变遗体所在。不要因为故事最后一段写了家属离开，就把死者也填进那条路上的同一组。
  每个placeKey只表达一个实际现场，opening也只描述该处；死者留在水下、家属走在高路就分别引用两个目录key，不能把家属的末尾场面套给死者。
  itemOutcomes中IN_SCENE表示物品留在一个粗粒度场景。新场景尚未分配编号时，直接给scene:{locationName:"与endingCast同名的实际场景"}，程序在建立场景后绑定编号；不要为凑sceneId填原来的岸上、屋内或出发地。只保存重要物品，普通背景小物不逐件跟踪。
  场景表示可以继续相互交谈的当前现场，不要求追踪集镇内每一步，但“已回屋写册”的人与“留在岸边船旁”的人已经分开，不能仅因都在同一码头就分到同组。即使离场发生在最后一句也必须承接；最后一句已离去，先前一起查看不表示仍在场。无论新旧地点均在endingPlaces写其名称，旧地点名称沿用before.locationIndex，服务器绑定编号。
  itemOutcomes只写重要物品本段实际归宿变化：{itemRef:已有编号可省略新物品编号,name,state:"HELD/IN_SCENE/CONSUMED",holderId:实际持有人,sceneId:实际留物地点,mode:"GIFT/LEND/RETURN/SEIZE/HOLD",source:真实新取得经过}。持有未变可省略；不因为描述了物品就再次获得一件。合法归属没有明确改变时用HOLD，不自作主张写成借用。背景物不逐件登记。
  playerJournal:{items:[{name,itemRef:可省略新物品编号,kind:"ARTIFACT/EQUIPMENT/KEY_ITEM/CONSUMABLE/KEEPSAKE/OTHER",important:true,description,knownEffects,limitations,significance,origin}],relationships:[{roleId,kind:"ALLY/FRIEND/ENEMY/RIVAL/FAMILY/ACQUAINTANCE/ESTRANGED",label,summary,basis}],agreements:[{kind:"ALLIANCE/CUSTODY/OTHER",counterpartId,status:"PROPOSED/ACTIVE/DECLINED/WITHDRAWN/ENDED/BROKEN/EXPIRED",basis,terms:可省略旧约定}]}。ALLIANCE仅正式盟约；代管物品用CUSTODY、其他普通约定用OTHER，不能因为状态为ACTIVE就归入盟约。资料只写玩家已知的事实；人物的隐秘态度不进入玩家面板。
  正式盟约只来自明确的既有TREATY记录或本段正文双方实际同意的结盟，不来自关系卡的ALLY枚举。同行、包扎、收礼、保管、友善合作、愿意协助本身不成立盟约。旧卡把临时协作者写成ALLY时可据实际交往改为FRIEND或ACQUAINTANCE，不编造结盟正文或额外义务；赠送的物品不等于对方承诺结盟。实际解除应写ENDED；只是准备联系、没有送达时不写成已解除。不把已解除者重新写成盟友。
    portraitStyle is optional presentation data: use CRIMSON_GOWN only when an adult or young adult human woman is publicly wearing a burgundy embroidered court gown. Otherwise omit it; never change the story or occupation to fit artwork.
    playerJournal可附characterAppearances:[{roleId,name,gender:"female/male/unknown",ageGroup:"CHILD/YOUNG/ADULT/ELDER/UNKNOWN",culturalStyle:"EASTERN/WESTERN/MODERN/SCIFI/MIXED",archetype:"WARRIOR/KNIGHT/SWORDSMAN/MAGE/NECROMANCER/ALCHEMIST/MECHANIC/PIRATE_CAPTAIN/VAMPIRE/NOBLE/CLERIC/DRAGON_RIDER/ELF/ROSE_ELF/TRAVELER/UNKNOWN",species:"human/elf/dwarf/dragonborn/vampire/other/unknown",portraitStyle:"CRIMSON_GOWN",divineRevealed:false,basis:"玩家已亲见外貌或已经公开的身份依据"}]，用于前端按类型配人物立绘。仅按已发表故事及主角已知事实记录公开外貌，实际未知则省略，不按姓名猜性别年龄，不为配图新造剧情。隐秘神明身份、猜测和伪装下的真身不写divineRevealed:true。既有外貌未变可省略。ROSE_ELF仅在已公开的红发精灵、玫瑰荆棘礼装等外貌依据明确时使用；普通精灵用ELF，不按心理或隐藏身份配图。此为可选展示资料，不影响剧情成立与保存。
    events只保留正文真实产生的重要环境FACT、身体BODY、伤亡治疗HEALTH等其他结果。FACT:{kind:"FACT",objectName,after}；BODY:{kind:"BODY",actorId,after:{awake或bound:布尔值}}；HEALTH:{kind:"HEALTH",actorId,health:0至100}。没有明确身体生命变化时保留原数值，不能因包扎这一词自动加固定生命；实际死亡写0。场景与物品结果不在events重复登记。
  conditionalContests只给条件性公式结果；对每项RESOLVED返回contestResolutions:[{fingerprint,occurred:正文中实际发生与否的布尔值}]，不能把意向当作发生，也不另判胜负。
  重要物品最终由谁实际拿着，就记录那个实际持有人；同伴、家属、所有者不自动等于持有人。正文只写未建卡的后生、救援队持物，不得为了凑已有编号把物品塞给母亲或其他熟人。若正文确有尚未给编号、却参与关键救援或持走重要物的角色，在newCharacterExperiences标出实际姓名或明确群体称呼与经过，供主创补简档取得意愿后定稿；不要自造编号或跳过这次实际交接。
  before.importantItemStates是此前已经跟踪的重要物品存档索引，包括不在当前现场的人持有的物品；ref是itemOutcomes.itemRef。它可能有旧归档错误，不是不可推翻的真相。逐项对照本篇及紧接的已发表交接原文：旧存档若仍把已经交到异地店铺的药材记在出发地货主身上，也在原ref修正实际店铺归宿，不能因本篇没有第二次交货而保留错误，不编造重复交接。主创据实际原文决定修正，不由程序按位置判定。无变化不抄全部清单，不新增背景零碎物。
  holderId表示当前实际持有人，不是法律所有者、雇主或委托人。正文交给异地店铺、机构的临时掌柜/办事人员而没有独立人物编号时，可用state:"IN_SCENE",scene:{locationName:正文实际收货店铺},source:"由哪位临时工作人员代表店铺收存"保存粗粒度归宿，不必为普通柜台人员增加角色调用，不得填成仍在出发地的原货主拿着。已有编号的实际持有人则直接用其holderId。before中的旧物品归档与已发表交付经过冲突时，依真实原文修正归宿，不编造第二次交货或退回；已付的报酬也不再领一次。
  roleExperiences:[{roleId,experience:"仅本人的实际新亲历",situation:"本段结束时累计重要处境，保留已有相关事实；区分已知和未知"}]。当面参与过本篇问答、拒绝、交付、分别的人，即使没有物品或位置变化，也须保存其本人确实听到的结果与最终处境；否则下轮本人会仍以旧问题待答。不能因不再活跃就省掉其离场前已经获知的结果。未参与也未获知者仍不更新，不从全局摘要给他补秘密。newCharacterExperiences:[{name,experience,situation}]为给定新人物按同样原则归档，也可明确指出已在正文出现但漏编号的关键参与者；不改其身份设定，不编正文没有的人物。
  before.openingPublishedChapter（若有）是最早已发布正文，不是尚待执行的角色初始设定；早期已经读过、交付过、确认过的事实不因摘要遗漏而变回未做。before.storyMemory是此前已保存的累计承接摘要，before.establishedStoryFacts是玩家已获知的重要原始事实，before.latestPublishedPassage是紧接本段的已发布前文。它们只用于保留既有事实、判明新旧，不是待执行计划，也不授权把玩家知道的内容教给未亲历的角色。continuity须在旧摘要上更新：保留仍影响后续的早期因果、已确认发现、已兑现结果、重要物品与关系归宿及真正未了的问题；本段解决的旧问题改为已解决，不遗忘后再当新谜。只保留有用信息，不逐段复述、不把普通询问变成新任务清单、不添加未来剧情。
  before.playerObjectives是玩家已有的阶段所求，不是必须执行的任务或终局门槛。依据本篇和已发表前文回写objectiveUpdates:[{objectiveId:"给定编号",status:"ACTIVE/COMPLETED/FAILED",currentStep:"实际已发生的进展及仍待处理之事"}]，只更新有变化者。完整正文中已完成的问答同样算实际发生，不要求额外对白日志；旧面板遗漏不把完成之事重做。没有发生的取物仍不能写完成；无法再实现时可记FAILED。目标完成不代表归隐或死亡，不新增琐碎任务、不以凑满目标驱动故事。
  chapterOutcome是主创给本篇的停点与待玩家决定的内容，不是玩家已经选择的结果。若为DECISION，新增提议只记录PROPOSED，不替玩家同意；未发表稿若先答应又询问，用publication修去代玩家表态的矛盾，停点和未决条件保留；已经发表的原文则只保留说法前后不一致、尚待玩家选择，不把它整理成已成立的重大约定。
  每个接续片段都以finalStory最后的实质进展更新原剧情和目标。之前尚未说明、待查、待议的事，本篇已经说明、查明或提出具体条件时，就回写原eventId/threadId及objectiveId，不能只更新角色经历却把剧情卡和目标卡留在上一片段。重要剧情编号按整个核心事件沿用，普通查账章程与路银等手续并入所属事件，不另建同等主线。原文里尚有口径冲突的数字或证词保留来源和不确定，不归档成已经证实的因果。
  玩家实际听完说明、看过证据、知道结果的剧情更新，同时填写playerView:{title,summary,status,category}；已回收伏笔也填写其playerView:{title,summary,status}，让限知卡反映本人已知进展。只写作者status或outcome不会更新玩家所知，不要因此遗漏；相关已发表前文中玩家已知、却未同步到旧卡的结果也可补回。幕后变化仍省略playerView，绝不直接把作者记录或未公开真相抄给玩家。
  playerKnownPlotStates单独列出界面当前显示的旧卡，逐一对照已发表正文中的玩家见闻；即使本篇已经转入新生活，旧卡仍写待查而玩家早已亲历结果的，也在原eventId或threadId补playerView。这只是同步已有见闻，不要让主角再调查一次，不自动复制作者状态。若仍有证词矛盾，就如实写明已听过的说法与未解之处，不假称全部证实。已实际抵达并取得工作的准备类目标应按原所求更新状态，不因标题仍写准备出发就一直保留未开始；新生活可继续，目标完成不是世界结束。
  逐项核对newCharacters与before.roles：去他家拜访后主角离开，不代表此人随主角回去了。正文能确定本人末态时，在endingCast按给定roleId写最后实际留在哪个粗粒度场景；仅被转述、提及，或无法确定末态者，placeKey写空字符串表示未确立地点，也可不填该行。不要为了凑齐角色表把他填到主角所在的placeKey；basis里说未在场、却仍填主角placeKey是矛盾的交接。旧记录若把被拜访者错误放在主角身边，依据已发表亲历修正本人实际所在，不编造再次离场或再次相见。
  玩家所知的旧卡补同步可以单独返回playerViewUpdates:{events:[{eventId,playerView:{title,summary,status,category}}],foreshadows:[{threadId,eventId可省略,playerView:{title,summary,status}}]}，即使作者剧情本段没有变化，也可据玩家已经亲历的旧结果更新其可见卡。只引用既有编号，未知内容省略；不会因此续写故事、改作者事实或泄露私心。plotUpdates仍保存真正的作者事实变化；两者可同时返回，不为更新界面往已结案的旧剧情塞入无关的新生活。
  目标状态只判断原标题所求是否达成。“出发准备、抵达求职”达成后，日后仍要按月工作、理旧账，不是让原准备目标永远ACTIVE的理由；不自动新增目标，不因原目标COMPLETED收束世界。
  输出continuityState:{accounts:[{key:"同一重要账目沿用稳定键",eventId:"已有剧情编号可省略",subject:"同一核账主体及原问题",receipts:[{amount:"数额含单位",from:"付款方",to:"收款方",purpose,status,source}],payments:[{amount,from,to,purpose,status,source}],otherClaims:[{text:"原文里的总额说法、记重等调整或先后说法",source}],unresolved:["具体哪条说法与哪些收付或先后事实相冲突"],conclusion:"在上面事实与冲突之后才能得出的结论"}]}。以同一主体分列收到与付出，再写仍有的矛盾，最后才写结论；旧entries格式仍可阅读。这里是重要剧情证据表，不是日常钱袋模拟。没有重要款项才留空，不登记每顿饭。原文或旧卡写“全部核清”只是一项待对照的说法，不能反过来决定每笔方向或让unresolved为空。例：核账主体同福记，德昌付给同福记六两须列receipts，同福记给木匠三两列payments；不能将它们合为九两支出，若人物合称九两已支未销，应在unresolved明确此总括与六两收入冲突，conclusion和公开卡也保留这项冲突。凭据先开银后付与银先付凭据后补同样不能都当一致事实。before.continuityState是已保存依据；更新同key给累计快照，已被明确推翻的旧说法改正，未解者保留。不要为了凑总数倒改收付方向或补造交易。
  再输出knownPlotReviews:[{eventId或threadId:"原编号",changed:布尔值,reason:"据哪些已发表见闻仍未变或已变化",playerView:{title,summary,status,category可选}}]。逐项复核playerKnownPlotStates列出的事件卡和伏笔卡，摘要以刚整理的收付事实及unresolved为依据，不先沿用旧卡“核清”后再用账户表圆回来。玩家已亲历的解释即使发生在旧章、本篇已转入生活，也补其公开卡；changed=true时给完整playerView，未变时false并省略playerView。只看玩家所知，不复制作者秘密，也不为补卡再生成故事。兼容旧playerViewUpdates和plotUpdates.playerView。
  已保存的账户沿用原key与收付依据，没有新事实时可省略整条账户更新。只有本篇真的取得此前未知的重要收付或凭据先后证据时，在该账户标newEvidence:true；普通再次提及不是新证据。新重要账目或新证据由同一主API聚焦整理一次来源，成功资料会缓存，日常回合不重复调用；sourceReview是系统保留标记，不自行填写。
  continuityState.consequences只记真正重要的选择及其持续后果，给同一件事沿用key的累计快照：[{key,kind:"ALLIANCE/GUARANTEE/HOSTILITY/DEFEAT/RELATIONSHIP/OTHER",participants:[角色编号],playerChoice:"已实际表达的选择或附带条件",actualOutcome:"正文实际接受、拒绝或发生的结果",lastingEffects:["以后仍有效的责任、态度变化、损失或帮助范围"],unresolved:["仍未决定或未兑现的部分"],status:"ACTIVE/RESOLVED/ENDED",basis:"正文实际依据"}]。before.decisionContext是前篇真实提议和本次玩家原话，先读完整条件，再按finalStory记实际后果。拒绝也是有效结果，不自动当敌对；有条件同意不扩大为无限担保。before.continuityState.consequences是累计旧记录，无变化可省略；有变化沿用key替换最新后果，已结束改状态并保留因果，不能每篇把已同意/已拒绝之事重新提议。普通吃住问路无需登记。不要从作者构思或角色意愿预造后果；不把幕后私心放入玩家资料。
  continuityState.narrativeState给下一篇紧凑交接：{currentSituation:"只写最终实际停点和仍有效状态，不复述全过程",actualTime:{to:"与最终定稿同一终点",milestones:[{event:"少量重要转折，保留实际日序范围",sourceQuote:"最终正文的连续短句"}]},completed:[{result:"仍影响后文的已兑现结果",basis:"原文短句或原始出处"}],open:[{question:"确实未解决且仍重要的问题",basis:"实际依据"}],characterChanges:[{roleId,change:"态度、关系或重要处境变化",cause:"本人实际知情原因"}]}。在旧快照上保留仍有效结果，已解决移出open。普通雨后看瓦、吃住提醒不为填表登记为悬而未决剧情。actualTime与个人记忆从最终正文取事实，不从时间basis或构思再演绎一遍；控制约500字，不补不存在的时刻和事件。
  归档保持紧凑：roleExperiences只记本人本篇新增亲历与仍有效处境，不复写完整旧档或心理表。knownPlotReviews会由程序合并公开卡，不必将同一playerView再次重复放进playerViewUpdates和plotUpdates；有新剧情发展才写plotUpdates的作者事实。没有变化的物品、关系、角色经历、旧后果可以省略，已有记录不会因此删除。
  continuity简要累计玩家已知的重要进展，不将某人说法升级为事实，不漏掉实际物品交接、离场与关系变化。输出一个JSON对象，写endingPlaces、endingCast、continuityState（先receipts/payments再unresolved再conclusion）、knownPlotReviews与其他有变化的结果。无变化的旧账、后果、物品、角色不复制；公开卡无变化只给编号和changed:false，不复写整张卡。narrativeState只给紧凑的当前停点、仍影响后文的结果与实际变化，勿逐段复述。没有变化可为空，不为填表虚构新进展。不返回顶层story，不改stop或玩家决策，不生成新的角色意愿。
  """;
 public static final String CONTRACT=StoryPlotPrompt.ARCHIVE+PublicAppearanceSchema.PROMPT+LEGACY_EDITORIAL_RULES+ARCHIVE_FIELDS;
 public static final String RECORD_CONTRACT=StoryPlotPrompt.ARCHIVE+PublicAppearanceSchema.PROMPT+"""
  你是主API的定稿归档阶段。finalStory是本次唯一已定稿正文；chapterOutcome给出同稿已接受的耗时与停点。本阶段只保存它的实际结果，不编辑正文、不再估时、不继续故事，也不生成角色意愿。
  角色各自所知、物品归属、人物末态和公开资料从同一份finalStory记录；before是先前资料，不能覆盖正文后来的实际变化。
  不返回publication、publicationReview、顶层story或另一份timeProgress。个人记忆与下一篇currentSituation承接真实末尾；文字中没写的行为、起讫和知情不由归档补造。
  """+ARCHIVE_FIELDS;
 public ObjectNode input(ObjectNode facts,ObjectNode draft){
  var in=JSON.createObjectNode();in.put("finalStory",draft.path("story").asText());in.set("playerId",facts.path("playerId"));
  in.put("finalPlaceFormat","NAMED_PLACES_V1");
  in.put("publicationMode",facts.path("finalManuscriptReady").asBoolean(false)?"FINAL_MANUSCRIPT_RECORD_ONLY":facts.path("allowPublicationEdits").asBoolean(false)?"UNPUBLISHED_COPYEDIT":"PUBLISHED_RECORD_ONLY");
  in.set("playerDirection",facts.path("playerDirection").deepCopy());
  if(facts.path("originStory").isTextual())in.set("originStory",facts.path("originStory").deepCopy());
  if(draft.path("manuscriptRelationships").isArray())in.set("manuscriptRelationships",draft.path("manuscriptRelationships").deepCopy());
  var outcome=in.putObject("chapterOutcome");outcome.set("stop",draft.path("stop").deepCopy());
  outcome.set("roleIds",draft.path("roleIds").deepCopy());
  outcome.put("participantMeaning","这是主创本篇实际参与名单，不是移动名单。结合finalStory为这些人物各自听见、亲历的结果保存roleExperiences；没有换地点或物品变化也可能已有新的答复与关系变化。未参与者不从全局摘要获得知识。");
  // The authored manuscript and its clock are one version. An unchanged archive
  // keeps that clock; a genuinely revised manuscript supplies its new duration.
  {
   if(draft.path("elapsedSeconds").isIntegralNumber())outcome.set("elapsedSeconds",draft.path("elapsedSeconds").deepCopy());
   if(draft.path("timeProgress").isObject())outcome.set("timeProgress",draft.path("timeProgress").deepCopy());
  }
  if(draft.path("decision").isObject())outcome.set("decision",draft.path("decision").deepCopy());
  in.set("playerKnownPlotStates",facts.path("plotMemory").path("playerKnownState").deepCopy());
  var before=in.putObject("before");for(String field:List.of("currentWorld","locationIndex","characterIndex","importantItemStates","playerJournal","playerObjectives","storyMemory","continuityState","decisionContext","establishedStoryFacts","plotMemory","plotResults","plotMemoryIssues","plotSourcePages","worldBookResults"))before.set(field,facts.path(field));
  before.set("storySeconds",facts.path("storySeconds").deepCopy());
  if(draft.path("authorPlan").isObject() || draft.path("authorPlan").isTextual())in.putObject("authorPlanning").put("status","UNCOMMITTED_AUTHOR_PLAN").set("content",draft.path("authorPlan").deepCopy());
  before.set("latestPublishedPassage",StoryAuthorContext.latestPublishedPassage(facts));
  var recent=before.putArray("previousChapters");for(var chapter:facts.path("previousChapters"))
   if(!chapter.path("story").equals(before.path("latestPublishedPassage")))recent.add(chapter.deepCopy());
  var earlier=before.putArray("earlierPublishedPassagesThisChapter");var committed=facts.path("alreadyHappenedThisChapter");
  for(int i=0;i<committed.size()-1;i++)earlier.add(committed.get(i).deepCopy());
  if(facts.has("openingPublishedChapter"))before.set("openingPublishedChapter",facts.path("openingPublishedChapter").deepCopy());
  var roles=before.putArray("roles");for(var entry:facts.path("characterDossiers").properties()){
   var dossier=entry.getValue();var role=roles.addObject().put("roleId",entry.getKey());role.set("name",dossier.path("name"));var current=role.putObject("currentState");for(String field:List.of("recordedSceneId","recordedLocation","storySituation","body","items"))if(dossier.path("currentState").has(field))current.set(field,dossier.path("currentState").path(field));
  }
  in.set("conditionalContests",facts.path("serverContests"));var fresh=in.putArray("newCharacters");
  for(var character:draft.path("newCharacters")){var identity=character.deepCopy();if(identity instanceof ObjectNode object)object.remove(List.of("experience","situation"));fresh.add(identity);}
  return in;
 }
 public ObjectNode project(ObjectNode facts,ObjectNode draft,String previousIssue){return project(facts,draft,previousIssue,raw->{});}
 public ObjectNode project(ObjectNode facts,ObjectNode draft,String previousIssue,java.util.function.Consumer<String> received){
  var messages=new ArrayList<Map<String,String>>();messages.add(Map.of("role","system","content",facts.path("finalManuscriptReady").asBoolean(false)?RECORD_CONTRACT:CONTRACT));messages.add(Map.of("role","user","content",input(facts,draft).toString()));
  if(facts.path("finalManuscriptReady").asBoolean(false))messages.add(Map.of("role","system","content","本次publicationMode=FINAL_MANUSCRIPT_RECORD_ONLY：finalStory已经由主API专注定稿，所给chapterOutcome时间也来自同一稿。这里只从这份完整稿整理实际结果，不再编辑正文或估另一份时间，不返回publication、publicationReview、timeProgress，不延伸故事。本人经历、最终场景、公开卡均以finalStory为准，完整记录实际参与者已听清的答复与后果。"));
  messages.add(Map.of("role","user","content","本次归档的阅读焦点（只保存原故事，不续写）："+reviewFocus(facts,draft)));
  if(previousIssue!=null && !previousIssue.isBlank())messages.add(Map.of("role","user","content","上次归档尚未写入，按publicationMode处理本篇发表前修订和结果记录，不修改已发表前文；保留原稿与已有意愿，不重抽正文。上次问题："+previousIssue));
  var stage=facts.path("finalManuscriptReady").asBoolean(false)?ModelProtocol.Stage.ARCHIVE:ModelProtocol.Stage.ARCHIVE_EDIT;
  var reply=LLMRequestPolicy.within(System.nanoTime()+65_000_000_000L,()->LLMRequestPolicy.STORY_ARCHIVE.call(()->model.chatForJson(ModelProtocol.messages(stage,messages))));
  String raw=Objects.toString(reply.parsedJson()!=null?reply.parsedJson():reply.content(),"");received.accept(raw);var projected=normalize(raw);requireTiming(projected,draft,facts);
  return completeAccounts(facts,draft,projected,received);
 }
 public ObjectNode completeAccounts(ObjectNode facts,ObjectNode draft,ObjectNode projected,java.util.function.Consumer<String> received){
  if(needsAccountSources(facts,projected)){
   try{projected=reconcileAccounts(facts,StoryPublicationRevision.apply(draft,projected,facts),projected);}
   catch(LLMApiException|IllegalArgumentException unavailable){projected.putObject("accountReview").put("status","UNREVIEWED").put("meaning","重要账目来源暂未整理完成，保留说法与原文，不视为程序证实，也不阻断正文。");for(var account:projected.at("/continuityState/accounts"))if(account instanceof ObjectNode object)object.put("sourceReview","UNREVIEWED");}
   received.accept(projected.toString());
  }
  return bindAuthoredTime(draft,projected,facts);
 }
 public ObjectNode bindAuthoredTime(ObjectNode draft,ObjectNode projection){return StoryPassageTime.bind(draft,projection);}
 public ObjectNode bindAuthoredTime(ObjectNode draft,ObjectNode projection,ObjectNode facts){
  var finalDraft=StoryPublicationRevision.apply(draft,projection,facts);
  var out=bindAuthoredTime(finalDraft,projection);var labels=projection.at("/publication/timeLabels");
  if((facts.path("allowPublicationEdits").asBoolean(false) || facts.path("finalManuscriptReady").asBoolean(false)) && out.path("timeProgress") instanceof ObjectNode time){
   boolean finalized="MANUSCRIPT_V1".equals(finalDraft.at("/publicationRevision/format").asText());
   var priorTime=bindAuthoredTime(draft,projection).path("timeProgress");
   var original=finalized && priorTime instanceof ObjectNode prior?prior.deepCopy():time.deepCopy();
   if(!finalized && labels.isObject())for(String field:List.of("from","to","dayNumbering","basis"))
     if(labels.path(field).isTextual() && !labels.path(field).asText().isBlank())time.set(field,labels.path(field).deepCopy());
   var start=StoryContinuationContext.startSource(facts);
   if(!start.isEmpty()){
    time.put("from","紧接上一段已发表正文的实际末尾（见startSource）");time.set("startSource",start);
    if(out.at("/continuityState/narrativeState/actualTime") instanceof ObjectNode actual){
     if(!actual.path("from").equals(time.path("from")) && !out.has("publicationOriginalNarrativeTime"))out.set("publicationOriginalNarrativeTime",actual.deepCopy());
     actual.set("from",time.path("from").deepCopy());actual.set("startSource",start.deepCopy());
     if(finalized && time.path("to").isTextual())actual.set("to",time.path("to").deepCopy());
    }
   }
   if(!original.equals(time))out.set("publicationAuthorOriginalTime",original);
  }
  return out;
 }
 boolean needsAccountSources(ObjectNode facts,ObjectNode projection){
  if("DONE".equals(projection.at("/accountReview/status").asText()))return false;
  for(var account:projection.at("/continuityState/accounts")){
   if(account.path("key").asText().isBlank())continue;boolean reviewed=false;
   for(var prior:facts.at("/continuityState/accounts"))if(account.path("key").equals(prior.path("key")) && "MAIN_API".equals(prior.path("sourceReview").asText()))reviewed=true;
   if(!reviewed || account.path("newEvidence").asBoolean())return true;
  }return false;
 }
 /** A focused continuation by the same main author, only for new important accounts/new evidence. No scene is rejected. */
 public ObjectNode reconcileAccounts(ObjectNode facts,ObjectNode draft,ObjectNode projection){
  var input=JSON.createObjectNode();input.set("accountsToOrganize",projection.path("continuityState").path("accounts").deepCopy());input.set("priorReviewedAccounts",facts.path("continuityState").path("accounts").deepCopy());
  input.set("publicCards",facts.path("plotMemory").path("playerKnownState").deepCopy());input.put("fixedCurrentStory",draft.path("story").asText());
  input.set("publicCardReviewTasks",reviewFocus(facts,draft).path("publicCardReviewTasks").deepCopy());input.set("draftPublicReviews",projection.path("knownPlotReviews").deepCopy());input.set("draftContinuity",projection.path("continuity").deepCopy());
  input.set("publishedChapters",facts.path("previousChapters").deepCopy());if(facts.has("openingPublishedChapter"))input.set("openingPublishedChapter",facts.path("openingPublishedChapter").deepCopy());input.set("earlierSourcePages",facts.path("plotSourcePages").deepCopy());
  String contract="""
   你仍是同一主API，接着整理定稿故事的重要账目来源。这里没有身体、场景或角色意愿表要填写，不续写故事，不改变任何人的选择。accountsToOrganize是刚才的候选整理，publicCards是旧界面，两者都可能错；已发表原文中的具体谁付款、谁收款先逐条读清，再看总括性结论。人物宣称核清、签字认可并不使前后相反的收付方向变一致；不要因全文说了“已查清”而替所有句子圆账。
   仅返回JSON {continuityState:{accounts:[{key:沿用原键,eventId:有关既有编号可用时保留,subject,receipts:[{amount,from,to,purpose,status,source}],payments:[{amount,from,to,purpose,status,source}],otherClaims:[{text,source}],unresolved:[具体矛盾],conclusion}]},knownPlotReviews:[{eventId或threadId:沿用publicCards的编号,changed:true或false,reason,playerView:{title,summary,status,category可选}}],continuity:"在draftContinuity上保留其他进展，修正与具体收付矛盾的旧总括"}。
   先按同一核账主体分列receipts与payments：款项明确从外人付给主体就是收入，主体给外人就是支出，账本两边各记一头不是两次交易。不要把主体付给木匠的工钱放在receipts。再将原文中的总额解释、凭据先后与这些具体事实对照。总括“九两已支未销”若包含一笔明确收到的六两，就在unresolved直说总括与六两收入方向冲突，不能只留下一个与此无关的疑点后继续称全部核清。凭据先开银后付与银先付凭据后补也必须保留原说法前后相反。不要为了制造疑云无端要求额外独立证人；这里只整理原文已有的明确矛盾，不凭空补交易、改口或新的证据。
   最后才给conclusion、每张相关公开卡及continuity：据原文已经听到的解释与仍有的明确矛盾分别写，不把存在矛盾的收付又总括为已支或全部核清。逐项处理publicCards内事件与伏笔，事件卡不代替伏笔卡。与账目无关的已定章程可以记已执行，但不要说因此数字和方向也全对。保留玩家已知范围，不公开人物私心，不把后来新生活塞进旧案。日常吃住零钱无需新增账户。key不新增或改名，已有重要工钱收讫可保留，不需虚构冲突。
   publicCardReviewTasks是逐个编号的清单，全部分别回答，尤其threadId不能被所属eventId替代。公开卡表示现在已知的状态，不是冻结的历史快照；后来已定且执行的章程不能以“当时未说”保留待定。draftPublicReviews是主归档刚给的候选：保留其中与金额无关且已由正文支持的正确进展，只有有据的部分才修正。凡九两伏笔摘要仍将六两收入合称支出，必须同样更新为已获说明但方向有矛盾，不能只修三十二两事件卡却保留伏笔旧总括。continuity也同步改正，不能账目表写存疑而累计摘要仍写全案均已核清。
   """;
  var reply=LLMRequestPolicy.within(System.nanoTime()+55_000_000_000L,()->LLMRequestPolicy.ACCOUNT_ARCHIVE.call(()->model.chatForJson(List.of(Map.of("role","system","content",contract),Map.of("role","user","content",input.toString())))));
  String raw=Objects.toString(reply.parsedJson()!=null?reply.parsedJson():reply.content(),"");var revised=normalize(raw);var out=projection.deepCopy();
  if(!revised.at("/continuityState/accounts").isArray() || revised.at("/continuityState/accounts").isEmpty())throw new IllegalArgumentException("重要账目来源整理未返回资料");
  var required=new HashSet<String>();for(var task:input.path("publicCardReviewTasks"))required.add(task.path("threadId").asText(task.path("eventId").asText()));var answered=new HashSet<String>();for(var review:revised.path("knownPlotReviews"))answered.add(review.path("threadId").asText(review.path("eventId").asText()));
  boolean complete=answered.containsAll(required) && !revised.path("continuity").asText().isBlank();
  for(var account:revised.at("/continuityState/accounts"))if(account instanceof ObjectNode object)object.put("sourceReview",complete?"MAIN_API":"UNREVIEWED");out.withObject("continuityState").set("accounts",revised.at("/continuityState/accounts").deepCopy());
  // Replace only explicitly reviewed public projections, retaining unrelated physical/author updates.
  var ids=new HashSet<String>();for(var review:revised.path("knownPlotReviews"))ids.add(review.path("threadId").asText(review.path("eventId").asText()));
  for(String collection:List.of("events","foreshadows")){
   String key="events".equals(collection)?"eventId":"threadId";
   for(var patch:out.path("plotUpdates").path(collection))if(patch instanceof ObjectNode object && ids.contains(patch.path(key).asText()))object.remove("playerView");
   if(out.path("playerViewUpdates").path(collection).isArray()){var kept=JSON.createArrayNode();for(var patch:out.path("playerViewUpdates").path(collection))if(!ids.contains(patch.path(key).asText()))kept.add(patch.deepCopy());((ObjectNode)out.path("playerViewUpdates")).set(collection,kept);}
  }
  var reviews=JSON.createArrayNode();for(var review:out.path("knownPlotReviews"))if(!ids.contains(review.path("threadId").asText(review.path("eventId").asText())))reviews.add(review.deepCopy());for(var review:revised.path("knownPlotReviews"))reviews.add(review.deepCopy());out.set("knownPlotReviews",reviews);
  if(!revised.path("continuity").asText().isBlank())out.set("continuity",revised.path("continuity").deepCopy());out.putObject("accountReview").put("status",complete?"DONE":"PARTIAL").put("rawResponse",raw);
  return normalize(out.toString());
 }
 ObjectNode reviewFocus(ObjectNode facts,ObjectNode draft){
  var focus=JSON.createObjectNode();var timing=focus.putObject("timing");timing.put("needsElapsedSeconds",StoryPassageTime.seconds(draft).isEmpty());timing.set("chapterStartedAtSeconds",facts.path("storySeconds").deepCopy());
  focus.put("publicationMode",facts.path("allowPublicationEdits").asBoolean(false)?"UNPUBLISHED_COPYEDIT":"PUBLISHED_RECORD_ONLY");
  focus.put("publicationReading","未发表模式先完整通读当前稿的先后、人物获知和已完成结果，修清含混衔接，给publication最终正文与其实际耗时，再从同一正文保存记忆。无修改用unchanged:true。不输出局部替换，不把格式问题写进小说。已发表模式只记录原文。");
  timing.put("previousPublishedPassageRef","/before/latestPublishedPassage").put("recentChaptersRef","/before/previousChapters").put("reviewField","publication.timeProgress").put("meaning","未修改正文时保留authoredTimeFrame的同版耗时，不重复估算夜间或次日。确实修订完整正文后才为修订稿提供新原单位耗时。起点仍接上一篇真实末尾。");
  var units=timing.putObject("secondsPerUnit");StoryDuration.SECONDS_PER_UNIT.forEach((unit,seconds)->units.put(unit,seconds));timing.put("preferredOutput","timeProgress.elapsedParts: 按原时间单位给amount/unit，程序换算；一个时辰是两小时。长期概述可直接用YEAR/MONTH/WEEK，单位折算沿用secondsPerUnit。模糊跨度由主API估计，程序不判断剧情可否发生。");
  var goals=focus.putArray("originalObjectivesToReview");for(var goal:facts.path("playerObjectives"))if("ACTIVE".equals(goal.path("status").asText()))goals.add(goal.deepCopy());
  focus.put("objectiveMeaning","逐项依据原所求回写objectiveUpdates；已达成不因后续生活仍在继续而一直ACTIVE。不得靠程序预定成功。");
  focus.put("savedPlayerPlotCardsRef","/playerKnownPlotStates");
  var tasks=focus.putArray("publicCardReviewTasks");int cardIndex=0;for(var card:facts.at("/plotMemory/playerKnownState")){
   int threadIndex=0;for(var thread:card.path("foreshadows")){tasks.addObject().put("threadId",thread.path("threadId").asText()).put("priorViewRef","/playerKnownPlotStates/"+cardIndex+"/foreshadows/"+threadIndex++).put("review","这是一张独立的伏笔卡，事件已复核不代替本卡复核；说明来源与矛盾分别保留。");}
   if(card.path("eventView").isObject())tasks.addObject().put("eventId",card.path("eventId").asText()).put("priorViewRef","/playerKnownPlotStates/"+cardIndex+"/eventView").put("review","只整理此事件自己的实际见闻，后来换城工作不写成旧查账事件的新进展。");cardIndex++;
  }
  var sourceRefs=focus.putArray("publishedEvidenceRefs");sourceRefs.add("/before/latestPublishedPassage").add("/before/previousChapters");if(facts.has("openingPublishedChapter"))sourceRefs.add("/before/openingPublishedChapter");sourceRefs.add("/before/plotSourcePages");
  focus.putObject("importantAccountsReview").put("previousStateRef","/before/continuityState").put("outputField","continuityState.accounts").put("read","按publishedEvidenceRefs原文逐笔区分主体、付款方与收款方，核对收到/付出及凭据先后的矛盾；不要只复制旧摘要或旧卡的‘核清’结论。涉及核心查账、工钱或真实矛盾的就整理账户快照；不涉及的才留空。旧账解决与主角另谋新生活分别记，双方说法前后相反要留在unresolved，不编新事实凑成一致。");
  focus.put("publicCardMeaning","根据玩家已发表的实际见闻逐项判断是否过期，必要时在playerViewUpdates补原编号的公开状态和摘要。与作者status分别保存，未知真相不公开；同一调用完成，不为补卡编新剧情。");
  focus.put("reviewOrder","未发表稿先输出publicationReview的少量原文阶段锚点与确需修清之处，再输出publication最终正文；未修改只给unchanged:true并保留作者耗时，修改后才给新稿耗时，然后才记录同一稿的结果。再给有新变化的重要accounts，最后整理公开卡和其他变化。未变卡只给编号、changed:false，不复写整卡，旧账无变化不复制。已发表稿只记录，全部不续写新剧情。");
  var manuscript=focus.putObject("publicationFocus");
  manuscript.put("readingBeforePublication","先在publicationReview以原句列出少量阶段锚点：概述到底覆盖哪段经过，后接具体场景是在展开其中一天还是又向后走，末尾细节与已过时日是否相容。确有歧义就修正文；不能看首尾同为七天便忽略中间。无需逐日展开或增加新故事；编辑依据不进入角色记忆和世界书。");
  manuscript.set("authoredTimeFrame",draft.path("timeProgress").deepCopy());
  manuscript.set("manuscriptEnding",ending(draft.path("story").asText()));
  manuscript.set("priorPublishedEnding",ending(StoryAuthorContext.latestPublishedPassage(facts).asText()));
  manuscript.put("read","从priorPublishedEnding接续，通读整篇而非只看日期字段。先理清每段概述覆盖到何时，再看紧随其后的具体日子是否是继续向后还是在展开已概述时段；有歧义就在最终正文澄清。正常省略日子与篇末总跨度总结不追加计时，不改成每天必写。返回publication完整最终正文或unchanged:true，再给这份最终正文的原单位耗时，后续个人经历和交接从它取事实。时间basis不添加新经过。只保存最终停点，不复活已完成问答、收付与任务，不替玩家决定新增重大承诺。已发表模式不改正文或时钟。");
  if(facts.path("finalManuscriptReady").asBoolean(false)){
   focus.put("publicationMode","FINAL_MANUSCRIPT_RECORD_ONLY").put("publicationReading","finalStory已经定稿，只记录同一正文的结果；不编辑、不重新估时。");
   focus.remove("publicationFocus");
   focus.put("reviewOrder","先整理endingPlaces与endingCast的实际末态，再记录各人本人亲历、重要物品、账目及公开卡；不返回另一份正文或时间。");
   timing.remove(List.of("reviewField","secondsPerUnit"));timing.put("needsElapsedSeconds",false).put("meaning","chapterOutcome已经绑定这份正文的同一时钟，归档只沿用。").put("preferredOutput","本阶段不再输出timeProgress或其他估时字段。");
  }
  return focus;
 }
 private TextNode ending(String story){
  var paragraphs=story.split("\\R\\s*\\R");int first=Math.max(0,paragraphs.length-5);
  return TextNode.valueOf(String.join("\n\n",Arrays.copyOfRange(paragraphs,first,paragraphs.length)));
 }
 public void requireTiming(ObjectNode projection,ObjectNode draft){
  requireTiming(projection,draft,JSON.createObjectNode());
 }
 public void requireTiming(ObjectNode projection,ObjectNode draft,ObjectNode facts){
  if(facts.path("allowPublicationEdits").asBoolean(false))StoryPublicationRevision.apply(draft,projection,facts);
  var publication=projection.path("publication");
  if(facts.path("allowPublicationEdits").asBoolean(false) && (publication.path("unchanged").asBoolean(false) || !publication.path("story").asText().isBlank()) && StoryDuration.seconds(publication.at("/timeProgress/elapsedParts")).isPresent())return;
  if(!duration(projection.at("/timeProgress/elapsedSeconds")) && StoryPassageTime.seconds(draft).isEmpty() && !duration(projection.path("elapsedSeconds")))throw new IllegalArgumentException("正文缺少耗时字段，请补写同一份最终正文实际经过的原数量和单位，不改写已发表前文");
 }
 private boolean duration(JsonNode value){return value.isIntegralNumber() && value.canConvertToLong() && value.asLong()>=0;}
 /** Omitted optional change collections mean no supplied update, not a failed story. */
 public static boolean hasMisplacedPlotEvents(String raw){
  try{for(var event:ModelOutput.object(raw).path("events"))if(plotRecord(event))return true;}catch(IllegalArgumentException ignored){}return false;
 }
 private static boolean plotRecord(JsonNode event){return event.isObject() && !event.hasNonNull("kind") && !event.path("eventId").asText().isBlank() && !event.path("category").asText().isBlank() && event.has("title");}
 private JsonNode archiveObject(String raw){
  try{return ModelProtocol.object(ModelProtocol.Stage.ARCHIVE,raw);}catch(IllegalArgumentException original){
   // A complete last object/array can be followed by an omitted outer object closer.
   // Accept only when adding exactly that delimiter parses the entire document;
   // never complete strings, values, inner structures, or truncated generation.
   String text=raw==null?"":raw.strip();
   if(text.startsWith("{") && (text.endsWith("}") || text.endsWith("]")))try{
   var out=ModelProtocol.object(ModelProtocol.Stage.ARCHIVE,text+"}");
    out.putObject("wireFormatRepair").put("kind","CLOSED_OUTER_OBJECT_AT_EOF").put("addedSuffix","}");return out;
   }catch(IllegalArgumentException invalid){}
   throw original;
  }
 }
 public ObjectNode normalize(String raw){
  var out=archiveObject(raw);
  // Some providers nest adjacent documented sections inside continuityState.
  // Move only known wire sections verbatim; never reinterpret their narrative meaning.
  if(out.path("continuityState") instanceof ObjectNode state)for(String field:List.of("publication","knownPlotReviews","continuity","timeProgress","playerViewUpdates","events","endingCast","endingPlaces","sceneOutcomes","itemOutcomes","roleExperiences","contestResolutions","newCharacterExperiences","playerJournal","objectiveUpdates","plotUpdates")){
   if(!out.hasNonNull(field) && state.hasNonNull(field))((ObjectNode)out).set(field,state.path(field).deepCopy());state.remove(field);
  }
  if(out.path("timeProgress") instanceof ObjectNode time){
   var parts=StoryDuration.seconds(time.path("elapsedParts"));Long seconds=parts.isPresent()?parts.getAsLong():null;
   if(seconds==null && duration(time.path("elapsedDays")) && duration(time.path("elapsedRemainderSeconds")))try{seconds=Math.addExact(Math.multiplyExact(time.path("elapsedDays").asLong(),86400L),time.path("elapsedRemainderSeconds").asLong());}catch(ArithmeticException ignored){}
   if(seconds!=null){if(time.has("elapsedSeconds") && time.path("elapsedSeconds").asLong()!=seconds && !time.has("reportedElapsedSeconds"))time.set("reportedElapsedSeconds",time.path("elapsedSeconds").deepCopy());time.put("elapsedSeconds",seconds);}
  }
  var arrays=List.of("events","sceneOutcomes","itemOutcomes","roleExperiences","contestResolutions","newCharacterExperiences");
  boolean archive=out.path("publication").isObject() || out.has("endingCast") || out.has("endingPlaces") || out.has("continuity") || out.has("playerJournal") || out.has("knownPlotReviews") || out.has("timeProgress") || out.has("continuityState") || arrays.stream().anyMatch(out::has);
  if(!archive)throw new IllegalArgumentException("回复未包含定稿归档资料，原回复已保存");
  for(String field:arrays){
   if(!out.hasNonNull(field))((ObjectNode)out).putArray(field);
   else if(!out.path(field).isArray())throw new IllegalArgumentException("定稿归档字段需为数组："+field);
  }
  // Both wire sections are called events. Move explicitly identified plot records to
  // their own section; never interpret their prose as a physical effect or drop it.
  var physical=((ObjectNode)out).arrayNode();for(var event:out.path("events")){
   if(!plotRecord(event)){physical.add(event);continue;}
   var plots=((ObjectNode)out).withObject("plotUpdates").withArray("events");boolean duplicate=false;for(var existing:plots)if(existing.equals(event))duplicate=true;if(!duplicate)plots.add(event.deepCopy());
  }((ObjectNode)out).set("events",physical);
  // Explicit review decisions share the public projection path; they do not
  // copy author status or infer knowledge from keywords in the story.
  for(var review:out.path("knownPlotReviews"))if(review.path("changed").asBoolean() && review.path("playerView").isObject()){
   String key=!review.path("threadId").asText().isBlank()?"threadId":"eventId";
   if(review.path(key).asText().isBlank())continue;
   var patches=((ObjectNode)out).withObject("playerViewUpdates").withArray("threadId".equals(key)?"foreshadows":"events");ObjectNode target=null;
   for(var existing:patches)if(existing instanceof ObjectNode object && review.path(key).equals(existing.path(key))){target=object;break;}
   if(target==null)target=patches.addObject();target.set(key,review.path(key).deepCopy());target.set("playerView",review.path("playerView").deepCopy());
  }
  // Display-only patches carry explicit model-projected player knowledge. They
  // cannot copy author facts or change an event's true status.
  for(String collection:List.of("events","foreshadows"))for(var cardPatch:out.path("playerViewUpdates").path(collection)){
   String key="events".equals(collection)?"eventId":"threadId",ref=cardPatch.path(key).asText();
   if(ref.isBlank() || !cardPatch.path("playerView").isObject() || cardPatch.at("/playerView/summary").asText().isBlank())continue;
   var patches=((ObjectNode)out).withObject("plotUpdates").withArray(collection);ObjectNode target=null;
   for(var existing:patches)if(existing instanceof ObjectNode object && ref.equals(existing.path(key).asText())){target=object;break;}
   if(target==null)target=patches.addObject().put(key,ref);
   var view=target.putObject("playerView");for(String field:List.of("title","summary","status","category"))if(cardPatch.path("playerView").path(field).isTextual())view.set(field,cardPatch.path("playerView").path(field).deepCopy());
  }
  if(!out.hasNonNull("playerJournal"))((ObjectNode)out).putObject("playerJournal");
  // Some replies put each person's actual change and its personally experienced cause
  // only in the documented handoff. Reuse that same per-person source, never global prose.
  for(var change:out.at("/continuityState/narrativeState/characterChanges")){
   String role=change.path("roleId").asText(),cause=change.path("cause").asText(),situation=change.path("change").asText();
   if(role.isBlank() || cause.isBlank() || situation.isBlank())continue;
   boolean recorded=false;for(var row:out.path("roleExperiences"))if(role.equals(row.path("roleId").asText()))recorded=true;
   if(!recorded)((ObjectNode)out).withArray("roleExperiences").addObject().put("roleId",role).put("experience",cause+"；"+situation).put("situation",situation).put("sourceFormat","narrativeState.characterChanges");
  }
  if(!out.hasNonNull("continuity"))((ObjectNode)out).put("continuity","");
  if(!out.path("playerJournal").isObject() || !out.path("continuity").isTextual())throw new IllegalArgumentException("定稿归档人物资料或承接摘要格式无法读取");return (ObjectNode)out;
 }
 public ObjectNode merge(ObjectNode draft,ObjectNode projection){return merge(draft,projection,new ObjectMapper().createObjectNode());}
 public ObjectNode merge(ObjectNode draft,ObjectNode projection,ObjectNode facts){
  projection=bindAuthoredTime(draft,projection,facts);
  var out=StoryPublicationRevision.apply(draft,projection,facts);for(String field:List.of("events","sceneOutcomes","itemOutcomes","roleExperiences","contestResolutions","playerJournal","continuity"))out.set(field,projection.path(field).deepCopy());
  out.remove(List.of("timeProgress","continuityState"));
  if(projection.path("continuityState").isObject())out.set("continuityState",projection.path("continuityState").deepCopy());
  if(duration(projection.at("/timeProgress/elapsedSeconds"))){out.set("timeProgress",projection.path("timeProgress").deepCopy());out.set("elapsedSeconds",projection.at("/timeProgress/elapsedSeconds").deepCopy());}
  else if(!duration(draft.path("elapsedSeconds")) && duration(projection.path("elapsedSeconds")))out.set("elapsedSeconds",projection.path("elapsedSeconds").deepCopy());
  out.set("sceneOutcomes",StoryArchiveLocations.project(projection,facts));
  out.remove("objectiveUpdates");if(projection.path("objectiveUpdates").isArray())out.set("objectiveUpdates",projection.path("objectiveUpdates").deepCopy());
  out.remove("plotUpdates");if(projection.path("plotUpdates").isObject()){
   var updates=projection.path("plotUpdates").deepCopy();var plan=draft.path("authorPlan");
   for(var raw:updates.path("events"))if(raw instanceof ObjectNode event){
    // Archiving can link the writer's plan, but cannot invent another future plot while projecting fixed prose.
    event.remove("authorNotes");boolean linked=event.path("useAuthorPlan").asBoolean();
    for(var ref:plan.path("eventIds"))if(!event.path("eventId").asText().isBlank() && ref.asText().equals(event.path("eventId").asText()))linked=true;
    if(linked && (plan.isObject() || plan.isTextual()))event.putObject("authorNotes").put("status","UNCOMMITTED_AUTHOR_PLAN").set("content",plan.deepCopy());
    event.remove("useAuthorPlan");
   }
   for(var raw:updates.path("foreshadows"))if(raw instanceof ObjectNode thread)thread.remove("authorNotes");
   out.set("plotUpdates",updates);
  }
  if(out.path("decision") instanceof ObjectNode decision){
   var source=draft.path("events").path(decision.path("eventIndex").asInt(-1));if(decision.path("counterpartId").asText().isBlank() && !source.path("actorId").asText().isBlank())decision.put("counterpartId",source.path("actorId").asText());decision.remove("eventIndex");
  }
  for(var character:out.path("newCharacters"))if(character instanceof ObjectNode object)for(var experience:projection.path("newCharacterExperiences"))if(character.path("name").equals(experience.path("name"))){for(String field:List.of("experience","situation"))if(experience.path(field).isTextual())object.set(field,experience.path(field));}
  StoryManuscriptReview.mergeRelationships(out,facts);
  return out;
 }
}
