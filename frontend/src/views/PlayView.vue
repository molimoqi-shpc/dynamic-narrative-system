<template>
 <main class="world-play paper-theatre refraction-play" :style="{...readingStyle,'--scene-art':`url(/art/paper-theatre-v1/stage-${atmosphere.key}.png)`}">
  <div class="rail-paper-frame" aria-hidden="true"><svg viewBox="0 0 70 70" fill="currentColor"><path d="M0 0H45C18 0 0 20 0 54Z" /></svg></div>
  <header class="world-header"><div class="world-brand"><CompassMark /><span>{{worldTitle}}<small>此身入局 · 众生同席</small></span></div><div class="world-location"><span>{{scene.storyTime || `第 ${scene.worldTick || 0} 刻`}}</span><strong>{{scene.locationName || '旅途将启'}}</strong></div><nav><PerspectiveModeSwitch :model-value="view.mode" :session-id="sessionId" :disabled="busy || loadingScene" @pending="onPerspectivePending" @change="onPerspectiveChanged" /><button class="mobile-options" @click="infoVisible=true" aria-label="打开旅程菜单">菜单</button><button v-if="isOmniscient && !perspectiveSwitching" @click="perspectiveVisible=true" aria-label="打开天眼观察台">天眼观察台</button><button class="mobile-scene-toggle" @click="scenePanelOpen=true">场景 <span aria-hidden="true">☰</span></button><button :disabled="busy" @click="rewindDialogVisible=true" aria-label="打开存档与回溯">存档与回溯</button><button :disabled="perspectiveSwitching" @click="worldBookVisible=true" aria-label="打开世诰">世诰</button><button @click="infoVisible=true" aria-label="查看世界与游玩说明">说明 <span aria-hidden="true">ⓘ</span></button><button :disabled="busy" @click="$router.push('/home')" aria-label="返回主菜单">归途 ↗</button></nav><JourneyMenu :pending="!!pendingOperation" /></header>
  <div class="world-layout">
   <CharacterStage :scene="scene" :atmosphere="atmosphere" :view-mode="view.mode" :busy="actionBlocked" :identity="`${sessionId}:${sessionStore.branchEpoch}:${view.mode}`" @talk="draftTalk" @book="worldBookVisible=true" @scene="scenePanelOpen=true" />
   <section class="story-stage" aria-label="故事与行动">
    <ManuscriptPaper />
    <div ref="storyViewport" id="story-reading-surface" class="story-reader" role="region" aria-label="故事正文" tabindex="0" @scroll="onStoryScroll">
     <button v-if="historyHasMore || historyError" class="older-story" :disabled="loadingHistory" @click="loadTranscript(true)">{{loadingHistory?'正在翻阅…':historyError?'重试读取故事':'翻阅更早的故事 ↑'}}</button>
     <header class="story-heading"><p>此生行记 <span>卷 / {{String(turns.at(-1)?.round || scene.worldTick || 0).padStart(2,'0')}}</span></p><span class="chapter-ghost" aria-hidden="true">{{String(turns.at(-1)?.round || scene.worldTick || 0).padStart(2,'0')}}</span><h1>{{scene.chapterTitle || scene.locationName || '旅途将启'}}<i aria-hidden="true">记</i></h1><FlightMark class="chapter-flight" /><span class="story-heading-line" aria-hidden="true" /></header>
     <article v-if="openingStory?.text" data-story-id="opening" class="opening-story"><p class="opening-label">序章 · 来路</p><div class="story-prose"><p v-for="(paragraph,i) in cleanText(openingStory.text).split(/\n\s*\n+/).filter(Boolean)" :key="i">{{paragraph}}</p></div></article>
     <details v-if="!turns.length && scene.sceneSummary" class="opening-cast scene-origin" open><summary>初至环境 · 当时的记录</summary><p class="story-introduction">{{cleanText(scene.sceneSummary.split("【当前局势】")[0])}}</p></details>
     <details v-if="!turns.length && scene.openingCharacters?.length" class="opening-cast" open>
      <summary>初至此地 · 人物与所在</summary>
      <p v-for="person in scene.openingCharacters" :key="person.id"><strong>{{person.name}}</strong> · {{cleanText(person.introduction)}}</p>
     </details>
     <div v-if="sceneLoadError" class="story-error" role="alert">此间暂未展开。<button @click="loadScene">重新加载</button></div>
     <p v-if="lifeClosed || scene.playerHealth>0 && scene.playerHealth<30" class="profile-note">当前生命 {{scene.playerHealth}} / 100<span v-if="sessionStore.status==='DEAD'"> · 已陨落，可阅读或回溯</span><span v-else-if="scene.playerHealth>0 && scene.playerHealth<30"> · 已身受重创，请谨慎行动或回溯</span></p>
     <div v-if="loadingHistory" class="history-status" role="status">正在翻开留存的故事…</div>
     <div class="story-chapters">
      <article v-for="(turn,index) in readingTurns" :key="turn.id" v-story-arrival="String(turn.id)===arrivingChapter" :data-story-id="String(turn.id)" class="story-chapter">
       <div class="chapter-mark"><span>{{turn.round?`第 ${turn.round} 回合`:`故事 · ${String(index+1).padStart(2,'0')}`}}</span><i /></div>
       <details class="decision-note"><summary><span>你的决定</span>{{cleanText(turn.playerInput)}}</summary><p>{{cleanText(turn.playerInput)}}</p></details>
       <div class="story-prose"><p v-for="(paragraph,paragraphIndex) in readableStory(turn).split(/\n\s*\n+/).filter(Boolean)" :key="paragraphIndex">{{paragraph}}</p></div>
       <p v-if="turn.inputNotice" class="composer-feedback" role="status">{{turn.inputNotice}}</p>
       <StoryDecisionNote :progress="turn.storyProgress" :current="index===readingTurns.length-1" />
       <GameChapterProgress :chapter="turn" />
       <details v-if="turn.usage?.calls || turn.usage?.legacy" class="chapter-record"><summary>本段模型费用估算 ¥{{Number(turn.usage.costCny || 0).toFixed(4)}}<span v-if="turn.usage.unconfirmedCalls"> · 有费用待核实</span></summary><p>已记录 {{turn.usage.total || 0}} tokens<span v-if="!turn.usage.legacy"> · {{turn.usage.calls}} 次请求</span>。按配置单价估算，以服务商账单为准。<span v-if="turn.usage.unconfirmedCalls">另有 {{turn.usage.unconfirmedCalls}} 次请求的费用尚未确认，不计作免费。</span></p></details>
       <p v-if="technicalFeedback(turn.technicalIssues)" class="composer-feedback" role="status">{{technicalFeedback(turn.technicalIssues)}}</p>
       <details v-if="(turn.actors || []).some(a=>a.godId!=='narrator' && !a.godId?.startsWith('system_') && a.displayedDialogue)" class="story-dialogue-record"><summary>查看这一段的原话 · 按时序</summary><template v-for="(actor,i) in turn.actors || []" :key="`quote-${turn.id}-${i}`"><p v-if="actor.godId!=='narrator' && !actor.godId?.startsWith('system_') && actor.displayedDialogue" class="story-dialogue"><span>{{actor.godName || displayName(actor.godId)}}</span>{{cleanText(actor.displayedDialogue)}}</p></template></details>
       <p v-if="!turn.actors?.length" class="story-prose">{{cleanText((turn.facts || []).join('；') || turn.outcomeLabel || '此间暂未出现新的可见变化。')}}</p>
       <details v-if="turn.statChanges?.length" class="chapter-record"><summary>这一段的六维变化</summary><p v-for="change in turn.statChanges || []" :key="change.key">{{statDefinitions.find(s=>s.key===change.key)?.label || change.key}} {{change.before}} → {{change.after}}（{{change.delta>0?'+':''}}{{change.delta}}）</p></details>
      </article>
     </div>
     <div v-if="busy && !loadingHistory" class="story-status" role="status" aria-live="polite"><span class="loading-orbit" aria-hidden="true" /><div><strong>{{waitingTitle}}</strong><p>{{waitingDescription}}</p></div></div>
     <p v-else-if="!turns.length && !sceneLoadError" class="story-opening-note">此间诸人各有其所求。观察、交谈，或让局势继续。<br>你随时可以介入。</p>
     <div class="story-end-mark" aria-hidden="true">✧</div>
    </div>
    <button v-if="hasNewStory" class="new-story-button" type="button" aria-controls="story-reading-surface" @click="scrollToLatest"><span aria-hidden="true">↘</span><strong>新内容已到</strong><small>{{unreadCount}} 段 · 继续阅读</small></button><p class="story-arrival-live sr-only" role="status" aria-live="polite" aria-atomic="true">{{hasNewStory?`有 ${unreadCount} 段故事新内容，可继续阅读。`:""}}</p>
    <section class="game-composer" aria-label="你的下一步行动">
     <div class="composer-top"><h2>{{busy?waitingTitle:lifeClosed?terminalHeading:composeMode==='talk'?'你想亲自说些什么？':storyChoice?'此刻，你如何回应？':'接下来，你打算怎样行动？'}}</h2><div v-if="!lifeClosed" class="composer-modes"><button v-for="mode in composeModes" :key="mode.key" :disabled="busy" :aria-pressed="composeMode===mode.key" @click="composeMode=mode.key">{{mode.label}}</button></div></div>
     <div v-if="!lifeClosed && activeDecisions.length" class="composer-decision" aria-live="polite"><span>此刻的交涉</span><p v-for="(point,i) in activeDecisions" :key="i">{{sceneNote(point.text,godNameMap)}}</p></div>
     <div v-if="!lifeClosed && composeMode==='talk'" class="composer-recipient"><label for="composer-target">交谈对象</label><select id="composer-target" v-model="knowledgeRecipient" :disabled="busy"><option value="">选择在场人物</option><option v-for="person in scene.presentGods || []" :key="person.godId" :value="person.godId">{{person.godName}}</option></select></div>
     <div v-if="loading || pendingIsGenerating" class="composer-feedback" role="status" aria-live="polite"><strong>{{pendingRecipient ? `正在与${pendingRecipient}交流` : '正在推进这一段故事'}} · 已等待 {{requestSeconds}} 秒</strong><p>{{pendingOperation?.status==='UNKNOWN'?'正在自动查询原操作，确认结果前不会重复发送行动。':'正在等待这一操作的叙事结果。你可以先阅读前文；结果到达后会接入正文。'}}</p></div>
     <div v-if="actionError" class="composer-feedback composer-failure" role="alert"><strong>{{actionError}}</strong><button :disabled="busy" @click="refreshActionResult">重新读取故事</button><button :disabled="busy" @click="actionError=''">收起提示</button></div>
     <div v-else-if="dialogueNotice && !loading && !pendingOperation" class="composer-feedback" role="status">{{dialogueNotice}}</div>
     <div v-if="syncRequired" class="composer-feedback" role="status"><strong>正在同步当前存档</strong><p>同步完成前暂停新行动，原输入已保留。</p><button :disabled="busy" @click="resyncCurrentBranch">重新读取当前进展</button></div>
     <div v-if="pendingOperation && !loading" class="composer-feedback operation-recovery" role="status">
      <strong>这一段尚有未完成内容</strong><p>{{pendingRecoveryNotice || '未收到结果。请查询已保存的操作，避免重复行动。'}}</p>
      <button :disabled="busy || queryingOperation" @click="queryOperation">{{queryingOperation?'正在查询…':'查询进度'}}</button>
      <template v-if="pendingOperation.pendingKind==='INPUT_REVIEW_REQUIRED'">
       <p v-for="(segment,index) in pendingOperation.inputReview?.privateSegments || []" :key="index">被理解为心想：{{segment}}</p>
       <button :disabled="busy || !pendingOperation.inputReview?.reviewId" @click="reviewInputMeaning('REINTERPRET_ACTION')">按行动意愿重新理解</button>
       <button :disabled="busy || !pendingOperation.inputReview?.reviewId" @click="reviewInputMeaning('KEEP_PRIVATE')">确认只保留心想</button>
       <button :disabled="busy" @click="editReviewedInput">取消并修改输入</button>
      </template>
      <template v-else><button :disabled="busy || queryingOperation || pendingOperation.status==='RUNNING'" @click="resumeOperation">{{pendingOperation.pendingKind==='SUBMISSION_NOT_REGISTERED'?'按原请求重试':pendingOperation.pendingKind==='MODEL_CALL_LIMIT'?'继续生成（允许追加模型用量）':'续接未完成部分'}}</button><button v-if="pendingOperation.pendingKind!=='SUBMISSION_NOT_REGISTERED'" :disabled="busy || queryingOperation" @click="cancelOperation">取消未完成部分</button></template>
     </div>
     <form class="composer-form" @submit.prevent="submitComposer"><div class="composer-entry"><textarea ref="actionInput" v-model="composeText" :disabled="actionBlocked || sessionStore.status!=='PLAYING' || sceneLoadError" maxlength="4000" rows="1" :placeholder="lifeClosed?'可从顶部打开存档与回溯，继续阅读或重新选择。':composeMode==='talk'?'写下要亲自对这个人物说的原话……':storyChoice?'这是需要你表态的重要时刻，输入你的选择或回应……':'写下接下来这一段的大致行动方向……'" aria-label="描述你的下一步" @keydown.ctrl.enter.prevent="submitComposer" @keydown.meta.enter.prevent="submitComposer" /></div><button type="submit" class="composer-submit" :disabled="actionBlocked || !scene.sceneId || !composeText.trim() || sessionStore.status!=='PLAYING' || (composeMode==='talk' && !knowledgeRecipient)">{{busy?'回应中…':composeMode==='talk'?'说出':'推进故事'}}<span aria-hidden="true">→</span></button></form>
     <div class="composer-foot"><div v-if="!lifeClosed" class="composer-hints"><button v-for="advice in sceneGuidance.slice(0,3)" :key="advice.key" :disabled="busy" @click="useAdvice(advice)">{{advice.label}}</button><button v-if="scene.waiting && ['PENDING','PAUSED'].includes(scene.waiting.status)" :disabled="busy" @click="continueWaiting">继续等待</button></div><span>{{composeText.length}} / 4000 <i>· Ctrl + Enter</i></span></div>
    </section>
   </section>
  </div>
  <footer class="theatre-tools" aria-label="旅程工具" @pointermove="lightMenu">
   <span class="rail-spine" aria-hidden="true" /><svg class="rail-plane-edge" viewBox="0 0 128 720" preserveAspectRatio="none" aria-hidden="true"><path d="M109 54 127 188" /></svg><div class="rail-etching" :style="{'--rail-art':railArt}" aria-hidden="true" />
   <nav aria-label="旅程导航"><button class="rail-story" :aria-current="activeNavigation==='story'?'page':undefined" @click="returnToStory">故事</button><button :aria-pressed="activeNavigation==='self'" :aria-expanded="activeNavigation==='self'" @click="openCharacter('self')"><span>人物</span></button><button :aria-pressed="activeNavigation==='items'" :aria-expanded="activeNavigation==='items'" @click="openCharacter('items')"><span>行囊</span></button><button :disabled="perspectiveSwitching" :aria-pressed="activeNavigation==='book'" :aria-expanded="worldBookVisible" @click="worldBookVisible=true"><span>世诰</span></button><button :aria-pressed="activeNavigation==='past'" :aria-expanded="historyOpen" @click="showPast"><span>往事</span></button><button :aria-pressed="activeNavigation==='stats'" :aria-expanded="activeNavigation==='stats'" @click="infoTab='六维';infoVisible=true"><span>六维</span></button><button :aria-pressed="activeNavigation==='scene'" :aria-expanded="scenePanelOpen" @click="scenePanelOpen=true"><span>场景</span></button></nav>
   <div class="rail-utilities"><button aria-controls="reading-preferences" :aria-expanded="readingOpen" @click="openReading"><span>此间</span><small>阅读</small></button><button aria-controls="music-console" :aria-expanded="musicOpen" @click="openMusic"><span>留声</span><small>音乐</small></button><StoryExportButton rail :session-id="sessionId"/><MainModelSelector :session-id="sessionId" :busy="busy || !!pendingOperation" @opened="readingOpen=false;closeMusicForModel()"/></div>
   <button class="theatre-self" @click="openCharacter('self')"><span>{{scene.playerName || '此身'}}<small>生命 {{scene.playerHealth ?? '—'}}</small></span></button>
  </footer>
  <ReadingSettings id="reading-preferences" v-model:open="readingOpen" v-model="readingPreferences" />
  <el-drawer v-model="historyOpen" title="往事 · 此生行记" size="min(1000px, 96vw)" class="story-history-drawer" destroy-on-close><StoryHistory :opening="openingStory" :turns="turns" :loading="loadingHistory" :error="historyError" :has-more="historyHasMore" :reading-style="readingStyle" :story-text="readableStory" :clean="cleanText" @older="loadTranscript(true)" @retry="loadTranscript()"><template #actions><StoryExportButton :session-id="sessionId" /></template></StoryHistory></el-drawer>
  <el-drawer v-model="scenePanelOpen" title="场景 · 此刻所在" direction="rtl" size="min(340px, 94vw)" class="scene-mobile-drawer"><GameSceneRail v-bind="railProps" @update:target="knowledgeRecipient=$event" @update:selected="selectedCoordKey=$event" @talk="draftTalk" @exit="draftExit" @share="draftKnowledgeShare" @treaty="draftTreaty" @question="draftKnowledgeQuestion" @verify="draftKnowledgeCheck" @inquire="onInquire" @return="onMoveCoord" @repair-objectives="showLegacyRepair" /></el-drawer>
  <el-drawer v-model="perspectiveVisible" title="天眼观察台 · 众生各有所求" size="min(950px, 96vw)" destroy-on-close><PerspectivePanel v-if="perspectiveVisible && isOmniscient && !perspectiveSwitching" :session-id="sessionId" :player-id="playerId" :view-mode="view.mode" :branch-epoch="sessionStore.branchEpoch" :refresh-key="`${sessionStore.sessionGeneration}:${sessionStore.branchEpoch}:${scene.revision || 0}:${worldBookRefresh}`" /></el-drawer>
  <el-drawer v-model="worldBookVisible" title="世诰 · 万象留痕" size="min(1180px, 98vw)" destroy-on-close class="world-book-drawer illuminated-book-drawer"><WorldBookPanel v-if="worldBookVisible && !perspectiveSwitching" :session-id="sessionId" :player-id="playerId" :view-mode="view.mode" :story-seconds="Number(scene.storySeconds || 0)" :story-time="scene.storyTime" :world-setting="scene.worldSetting" :initial-query="bookInitialQuery" :opening-characters="scene.openingCharacters || []" :refresh-key="`${sessionStore.sessionGeneration}:${sessionStore.branchEpoch}:${scene.revision || 0}:${worldBookRefresh}`" /></el-drawer>
  <el-dialog v-model="infoVisible" title="旅程 · 游玩说明" width="min(720px, 94vw)" class="game-info-dialog"><nav class="info-tabs"><button v-for="tab in ['游玩','世界','六维','回顾']" :key="tab" :aria-pressed="infoTab===tab" @click="infoTab=tab">{{tab}}</button></nav>
   <section v-if="infoTab==='游玩'" class="info-section"><p class="info-kicker">众生同席，各有所求</p><h3>你是参与者，亦有自己的来路。</h3><p class="journey-shortcuts"><button class="info-link" :disabled="busy" @click="infoVisible=false;rewindDialogVisible=true">存档与回溯</button><button class="info-link" :disabled="busy" @click="$router.push('/home')">返回主菜单</button></p><p>用自己的话行动，向在场人物交流，沿已知出口前行。选择等待时，世界中的人物也会继续自己的事情。</p><p>行动提示只会填入草稿，确认后才会发送。关键承诺、交易和风险选择，由你亲自决定。</p><p class="info-muted">当前视角：{{isOmniscient?'天眼 · 全知':'入梦 · 限知'}}。通过顶部视角按钮切换。入梦只查阅主角自己的见闻；天眼额外开放角色心理、真实属性、持物、独立记忆与裁决记录。切换不重新生成正文，也不会把幕后信息教给角色。</p><button v-if="isOmniscient && !perspectiveSwitching" class="info-link" @click="infoVisible=false;perspectiveVisible=true">打开天眼观察台</button></section>
   <section v-else-if="infoTab==='世界'" class="info-section"><h3>{{worldTitle}}</h3><p>{{scene.worldSetting?.summary || "每个人依据自己的所见、所闻与所求选择行动，转述与猜测并不自动成为真相。"}}</p><p v-if="scene.worldSetting?.namingBasis" class="world-naming">{{scene.worldSetting.namingBasis}}</p><p>查看右侧的见闻来源，向具体人物追问，或核对现场记录。未被你获知的秘密，不会直接成为你的线索。</p><h4>此刻所在</h4><p>{{scene.locationName}} · {{cleanText(scene.sceneSummary)}}</p></section>
   <section v-else-if="infoTab==='六维'" class="info-section"><h3>六维相生</h3><div class="info-stat-grid"><button v-for="stat in statDefinitions" :key="stat.key" class="stat-aura" @click="infoVisible=false;showStatFormula(stat.key)"><span>{{stat.label}}</span><strong>{{scene.playerActualStats?.[stat.key] ?? '—'}}</strong><small>{{stat.description}}</small></button></div><p class="info-muted">本为能力根基，归为忠诚意志。点击任一维查看当前计算公式与明细。</p></section>
   <section v-else class="info-section"><h3>翻阅此生，回到一刻。</h3><p>中央故事可以向上回顾；右侧收录见闻、约定和已知地点。回溯会恢复保存时刻的状态，从那里重新选择。</p><button class="info-link" :disabled="busy" @click="infoVisible=false;rewindDialogVisible=true">打开时空回溯 →</button></section>
  </el-dialog>
    <StatFormulaDialog v-model="formulaDialogVisible" :book="scene.playerFormula" :initial-key="formulaKey" />

    <!-- 6.4 时空回溯 dialog（事件溯源读档） -->
    <el-dialog v-model="rewindDialogVisible" title="时空回溯" width="min(860px, 94vw)" align-center class="chronicle-dialog">
     <header class="chronicle-frontispiece"><EtchedOrnament kind="time"/><div><p class="utility-kicker">留存之刻 / 另一个后来</p><h2>回到一页，从此改写。</h2><p>选择已留存的回合，恢复当时的状态，继续你的旅程。</p></div></header>
     <div class="chronicle-toolbar"><span>当前回合 <strong>{{savePoints.currentRound || 0}}</strong></span><el-button text size="small" @click="loadSavePoints">刷新存档点 ↻</el-button></div>
     <div v-if="!savePoints.savePoints?.length" class="chronicle-empty"><EtchedOrnament kind="time"/><h3>尚无可以重返的一刻。</h3><p>已保存的故事片段，会在这里留下回溯入口。</p></div>
     <ol v-else class="chronicle-timeline" aria-label="可回溯的故事片段"><li v-for="point in savePoints.savePoints" :key="point.roundNo"><span class="chronicle-round"><small>第</small><strong>{{point.roundNo}}</strong><small>回合</small></span><div><h3>{{point.locationName || point.sceneName || '留存的一页'}}</h3><p>{{point.playerInput || '此刻的故事已留存。'}}</p></div><el-button type="danger" size="small" :loading="rewinding" :disabled="busy || syncRequired" @click="loadToRound(point.roundNo)">从此刻继续 ↗</el-button></li></ol>
     <p class="chronicle-footnote">查阅存档不会推进故事。回溯后，后来的选择将从这一页重新展开。</p>
    </el-dialog>

    <!-- Step 5：死亡总结 dialog -->
    <el-dialog v-model="deathDialogVisible" :title="sessionStore.status==='RETIRED' ? '此生归处' : sessionStore.status==='VICTORY' ? '旧版通关' : '天命已尽'"
               width="min(860px, 94vw)" :close-on-click-modal="false" :close-on-press-escape="false"
               :show-close="true" class="ending-dialog engraved-ending" append-to-body align-center>
      <header class="ending-frontispiece"><EtchedOrnament kind="time"/><p class="utility-kicker">此生终章 / 留存之卷</p><h2>{{sessionStore.status==='RETIRED'?'归隐':sessionStore.status==='VICTORY'?'旧章':'挽歌'}}</h2><span>故事留存，来路有声。</span></header>
      <div class="ending-manuscript"><div class="ending-narrative"><p v-for="(paragraph,index) in (deathSummary || (pendingOperation?'正在根据世诰整理此生终章；若生成暂停，可关闭此窗续接原操作。':'此生终章尚未生成。')).split(/\n\s*\n+/).filter(Boolean)" :key="index">{{paragraph}}</p></div><blockquote v-if="finalEvaluation" class="ending-evaluation"><small>此生回响</small><p>{{finalEvaluation}}</p></blockquote></div>
      <template #footer>
        <div class="ending-actions">
          <el-button @click="deathDialogVisible=false">阅读故事与存档</el-button>
          <el-button @click="restartGame(true)" :loading="restarting" :disabled="!!pendingOperation" type="primary">
            续承世诰，再启新生
          </el-button>
          <el-button @click="restartGame(false)" :loading="restarting" :disabled="!!pendingOperation">
            重新化生
          </el-button>
          <el-button @click="deathDialogVisible = false; rewindDialogVisible = true"
                     style="color:var(--myth-cinnabar);">
            回溯时空
          </el-button>
          <el-button @click="$router.push('/home')" :disabled="restarting">
            返回主菜单
          </el-button>
        </div>
      </template>
    </el-dialog>
  <el-dialog v-model="legacyRepairDialog" title="重订旧版开局目标" width="min(560px, 94vw)" :close-on-click-modal="false">
   <p>旧目标的“探明、辨清、理解”无法由原规则验证。采用下列目标后，将按实际观察与到访记录判定完成，原目标与规则保留，可回溯到重订前。</p>
   <p>原目标：{{(legacyRepair.originalTitles || []).join('、')}}</p>
   <ul><li v-for="goal in legacyRepair.replacements || []" :key="goal.title"><strong>{{goal.title}}</strong>：{{goal.condition}}（{{goal.satisfied?'已有完成证据':'尚未满足'}}）</li></ul>
   <p>替代目标只记录阶段成果，完成后仍可继续游玩。只有实际死亡或主动选择且在剧情中实现的归隐终老才收束此生。重订不消耗模型调用，也不推进故事时间。</p>
   <template #footer><el-button :disabled="loading" @click="legacyRepairDialog=false">保留原目标</el-button><el-button :disabled="actionBlocked || !legacyRepair.available" @click="applyLegacyRepair">{{legacyRepair.mayFinish?'采用新目标并结束本局':'采用新目标'}}</el-button></template>
  </el-dialog>
 </main>
</template>
<script setup>
import CharacterStage from '../components/CharacterStage.vue'
import ManuscriptPaper from '../components/ManuscriptPaper.vue'
import FlightMark from '../components/FlightMark.vue'
import CompassMark from '../components/CompassMark.vue'
import JourneyMenu from '../components/JourneyMenu.vue'
import ReadingSettings from '../components/ReadingSettings.vue'
import MainModelSelector from '../components/MainModelSelector.vue'
import {characterPanelPresentation,navigationSelection} from '../utils/panelPresentation'
import {currentMotion} from '../utils/motionPreferences'
import {createStoryReading} from '../utils/storyReading'
import {storyArrival as vStoryArrival} from '../utils/storyArrival'
import EtchedOrnament from '../components/EtchedOrnament.vue'
import StoryHistory from '../components/StoryHistory.vue'
import StoryExportButton from '../components/StoryExportButton.vue'
import {loadReading,saveReading,readingVariables} from '../utils/readingPreferences'
import {sceneBackdrop} from '../utils/characterPortrait'
import TheatreGlyph from '../components/TheatreGlyph.vue'
import GameChapterProgress from '../components/GameChapterProgress.vue'
import StoryDecisionNote from '../components/StoryDecisionNote.vue'
import GameSceneRail from '../components/GameSceneRail.vue'
import WorldBookPanel from '../components/WorldBookPanel.vue'
import PerspectivePanel from '../components/PerspectivePanel.vue'
import PerspectiveModeSwitch from '../components/PerspectiveModeSwitch.vue'
import {sceneAtmosphere} from '../utils/sceneAtmosphere'
import {shareKnowledgeDraft,questionKnowledgeDraft} from '../utils/knowledgeDrafts'
import {visibleText,actorName,sceneNote} from '../utils/visibleText'
import StatFormulaDialog from '../components/StatFormulaDialog.vue'
import { ref, computed, onMounted, onBeforeUnmount, nextTick, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useViewModeStore } from '../store/viewMode'
import { useSessionStore } from '../store/session'
import http from '../api'
import {buildSceneGuidance,statDefinitions,statChanges} from '../utils/sceneGuidance'
import { dialogueFeedback, technicalFeedback } from '../utils/turnFeedback.js'
import {requestIdentity} from '../utils/requestIdentity.js'
import { canApplyOperation, mergeOperationChapter, operationNotice, reconcilePendingOperation, replayableSubmission } from '../utils/gameOperation.js'
import { ElMessage, ElAside, ElButton, ElCard, ElCollapse, ElCollapseItem, ElContainer, ElDrawer, ElDialog, ElDivider, ElInput, ElOption, ElProgress, ElSelect, ElSwitch, ElTable, ElTableColumn, ElTag, ElTooltip } from 'element-plus'

const router = useRouter()
const view = useViewModeStore()
const sessionStore = useSessionStore()

const isOmniscient = computed({
  get: () => view.isOmniscient,
  set: v => view.setMode(v ? 'OMNISCIENT' : 'DREAMER')
})

// 从 SessionStore 读 playerId / sessionId，删 hardcode
const playerId = computed(() => sessionStore.playerId)
const sessionId = computed(() => sessionStore.sessionId)

const scene = ref({})
const legacyRepair=ref({}),legacyRepairDialog=ref(false)
let legacyRepairSequence=0
const formulaDialogVisible=ref(false)
const formulaKey=ref('zhi')
function showStatFormula(key) {formulaKey.value=key;formulaDialogVisible.value=true}
const turns = ref([]),openingStory=ref(null),historyOpen=ref(false)
const readingPreferences=ref(loadReading(window.localStorage)),readingOpen=ref(false),musicOpen=ref(false)
const readingStyle=computed(()=>readingVariables(readingPreferences.value))
const worldTitle=computed(()=>scene.value.worldSetting?.title || '此生行记')
const railArt=computed(()=>{const src=sceneBackdrop(scene.value,atmosphere.value);return src?`url(${src})`:'none'})
watch(readingPreferences,value=>saveReading(window.localStorage,value),{deep:true})
watch(worldTitle,title=>document.title=title+' · 此生行记',{immediate:true})
watch(()=>[sessionStore.sessionId,sessionStore.branchEpoch],()=>{openingStory.value=null;historyOpen.value=false},{flush:'sync'})
function openReading(){readingOpen.value=!readingOpen.value;if(readingOpen.value){window.dispatchEvent(new Event('close-music-panel'));window.dispatchEvent(new Event('close-main-model-panel'))}}
function closeMusicForModel(){window.dispatchEvent(new Event('close-music-panel'))}
function musicPanelState(event){musicOpen.value=!!event.detail?.open;if(musicOpen.value)readingOpen.value=false}
const diagnostics=ref(false)
function cleanText(s){return visibleText(s,godNameMap.value)}
function displayName(id){return actorName(id,godNameMap.value)}
function continueWaiting(){if(busy.value)return;playerAction.value=scene.value.waiting.originalText;onSubmit()}
const tokens = computed(()=>{const usage=turns.value.at(-1)?.usage || {};return {input:usage.input || 0,output:usage.output || 0,total:usage.total || 0,cost:Number(usage.costCny || 0).toFixed(4),unconfirmed:usage.unconfirmedCalls || 0}})
const playerAction = ref('')   // 动作输入（原 playerText 改名）
const playerMovement = ref('') // 移动输入（新增）
const loading = ref(false)
// A free status read must not blink the composer, story loader and recovery
// controls as if it were another submitted action every time polling runs.
const queryingOperation=ref(false)
const pendingOperation=computed(()=>sessionStore.pendingOperation)
const pendingRecoveryNotice=computed(()=>{
 const operation=pendingOperation.value;if(!operation)return ''
 const notice=operationNotice(operation.status,operation.pendingKind)
 // Resource failures and input reviews need their specific remedy; otherwise
 // explain the actual incomplete stage, including when no prose exists yet.
 return operation.status==='PARTIAL' && !/^(MODEL_|INPUT_REVIEW_REQUIRED)/.test(operation.pendingKind || '')
  ?technicalFeedback(operation.technicalIssues) || notice:notice
})
const pendingIsGenerating=computed(()=>['RUNNING','UNKNOWN'].includes(pendingOperation.value?.status))
const syncRequired=ref(false)
const actionBlocked=computed(()=>busy.value || syncRequired.value || !!pendingOperation.value)
const lifeClosed=computed(()=>['DEAD','RETIRED','VICTORY'].includes(sessionStore.status))
const terminalHeading=computed(()=>sessionStore.status==='DEAD'?'此生已尽，故事仍可翻阅':sessionStore.status==='RETIRED'?'此生归隐，旧梦仍可重温':'旧卷已收束，可阅读或回溯')
let operationPoll=null,operationViewAlive=true,operationPollFailures=0
const operationRequestTimeout=20_000
function scheduleOperationPoll(){
 clearTimeout(operationPoll)
 if(!operationViewAlive || !pendingIsGenerating.value)return
 operationPoll=setTimeout(async()=>{if(!busy.value)await queryOperation();scheduleOperationPoll()},Math.min(15000,2000*2**Math.min(operationPollFailures,3)))
}
watch(pendingOperation,scheduleOperationPoll,{deep:true})
watch(()=>[sessionId.value,scene.value.branchEpoch,scene.value.revision,pendingOperation.value?.operationId],loadLegacyRepair)
onBeforeUnmount(()=>{operationViewAlive=false;clearTimeout(operationPoll)})
const actionError=ref(''),dialogueNotice=ref(''),pendingRecipient=ref(''),requestSeconds=ref(0)
let requestTimer=null,requestClockKey=null,requestStartedAt=0
watch(()=>[loading.value,pendingOperation.value?.operationId,pendingOperation.value?.status],()=>{
 clearInterval(requestTimer);requestTimer=null
 if(!loading.value && !pendingIsGenerating.value){requestClockKey=null;return}
 const key=pendingOperation.value?.operationId || 'request'
 if(requestClockKey!==key){requestClockKey=key;requestStartedAt=Number(pendingOperation.value?.startedAt) || Date.now()}
 const update=()=>requestSeconds.value=Math.max(0,Math.floor((Date.now()-requestStartedAt)/1000))
 update();requestTimer=setInterval(update,1000)
})
onBeforeUnmount(()=>clearInterval(requestTimer))
async function refreshActionResult(){if(busy.value)return;if(syncRequired.value)return resyncCurrentBranch();if(pendingOperation.value)return queryOperation();if(await resyncCurrentBranch()){dialogueNotice.value='已重新读取保存的故事，请确认最新进展后再行动。'}}
const exploring = ref(false)
const movingCoord = ref(false)
const inquiring = ref(false)            // Step 4 打听 loading
const visitedLocations = ref([])        // Step 4 已访问地点列表
const selectedCoordKey = ref('')        // Step 4 下拉选中坐标 "x,y"
const deathDialogVisible = ref(false)   // Step 5 死亡总结 dialog
const deathSummary = ref('')            // Step 5 死亡总结文本
const finalEvaluation = ref('')          // Step 5 天道评价
const restarting = ref(false)            // Step 5 重开按钮 loading

// 6.4 事件溯源：时空回溯
const rewindDialogVisible = ref(false)
const rewinding = ref(false)
const savePoints = ref({ currentRound: 0, savePoints: [] })

function readableStory(turn) {
 const actors=turn.actors || []; const prose=actors.filter(a=>a.godId==='narrator' || a.godName==='旁白' || a.godId?.startsWith('system_')).map(a=>{const text=cleanText(a.displayedDialogue);return a.godId==='narrator'?text.replace(/^narrator\s*[:：][ \t]*/gm,''):text}).join('\n\n');
 // Old saves may not yet contain inline speech; keep it visible without opening an appendix.
 const missing=actors.filter(a=>a.godId!=='narrator' && a.godName!=='旁白' && !a.godId?.startsWith('system_') && a.displayedDialogue && !prose.includes(cleanText(a.displayedDialogue))).map(a=>`${a.godName || displayName(a.godId)}说：“${cleanText(a.displayedDialogue)}”`);
 return [prose,...missing].filter(Boolean).join('\n\n');
}
// An unfinished operation without prose is not yet a chapter. Keep recovery in the
// composer until actual prose arrives, leaving the published story readable.
const readingTurns=computed(()=>turns.value.filter(turn=>!['RUNNING','PARTIAL'].includes(turn.operationStatus) || readableStory(turn).trim() || turn.facts?.length || turn.outcomeLabel))
const godNameMap = ref({narrator:"旁白"})
const loadingScene=ref(true)
const sceneLoadError=ref(false)
let nextTurnId=0
const busy=computed(()=>perspectiveSwitching.value || loading.value || exploring.value || movingCoord.value || inquiring.value || rewinding.value || restarting.value || loadingScene.value || loadingHistory.value)

const knowledgeRecipient=ref('')
function draftKnowledgeShare(entry) {
  const person=(scene.value.presentGods || []).find(g=>g.godId===knowledgeRecipient.value)
  if(!person || busy.value) return
  playerAction.value=shareKnowledgeDraft(person,entry)
  composeMode.value='action';focusComposer()
}
function draftKnowledgeQuestion(entry) {
  const person=(scene.value.presentGods || []).find(g=>g.godId===knowledgeRecipient.value)
  if(!person || busy.value) return
  playerAction.value=questionKnowledgeDraft(person,entry)
  composeMode.value='action';focusComposer()
}
function draftKnowledgeCheck(entry) {
  if(busy.value) return
  playerAction.value=`我细查现场已有的公开记录，核验「${entry.title}」的原始来源，不把转述当成事实。`
  composeMode.value='action';focusComposer()
}
const activeDecisions=computed(()=> (turns.value.length ? turns.value.at(-1).activeDecisionPoints ?? turns.value.at(-1).decisionPoints ?? [] : scene.value.activeDecisionPoints ?? scene.value.decisionPoints ?? []).slice(-2))
const storyChoice=computed(()=>turns.value.at(-1)?.storyProgress?.requiresInput ? turns.value.at(-1).storyProgress : null)
const sceneGuidance=computed(()=>buildSceneGuidance(scene.value))
const waitingTitle=computed(()=>loadingHistory.value ? '正在翻阅旧卷…' : rewinding.value ? '正在回溯旧梦…' : restarting.value ? '正在开启新命途…' : loadingScene.value ? '正在展开此间…' : exploring.value || movingCoord.value ? '正在探寻前路…' : inquiring.value ? '正在打听往事…' : '故事正在回应…')
const waitingDescription=computed(()=>loading.value || exploring.value ? '正在等待叙事结果，可能需要几十秒。请勿重复发送。' : '正在读取或保存旅程，请稍候。')
function useAdvice(advice) {
  if (busy.value || sceneLoadError.value || sessionStore.status!=='PLAYING') return
  if(advice.kind==='movement'){composeMode.value='move';playerMovement.value=advice.text}
  else{composeMode.value='action';playerAction.value=advice.text}
  focusComposer()
}

const perspectiveSwitching=ref(false),perspectiveVisible=ref(false)
function clearPrivateView(){sessionStore.setMemoryContext('');sessionStore.setNpcDecisionHistory([]);sessionStore.setLastNpcDecisions([])}
function onPerspectivePending(pending){perspectiveSwitching.value=pending;if(pending){clearPrivateView();perspectiveVisible.value=false;worldBookVisible.value=false;sceneRequestSequence++}}
function onPerspectiveChanged(mode){view.setMode(mode);worldBookRefresh.value++;clearPrivateView();loadScene();ElMessage.info(mode==='OMNISCIENT'?'已开启天眼，可查阅幕后资料。':'已回到入梦，只查阅主角见闻。')}



const storyViewport=ref(null),actionInput=ref(null)
const storyReading=createStoryReading(()=>storyViewport.value,()=>turns.value.filter(turn=>readableStory(turn).trim() || turn.facts?.length || turn.outcomeLabel).map(turn=>({id:turn.id,signature:readableStory(turn)+'\n'+(turn.facts || []).join('；')+'\n'+(turn.outcomeLabel || '')})))
const {hasNewStory,unreadCount,arrivingChapter}=storyReading
onBeforeUnmount(storyReading.dispose)
watch(()=>[sessionStore.sessionId,sessionStore.branchEpoch],storyReading.reset,{flush:'sync'})
const scenePanelOpen=ref(false),infoVisible=ref(false),infoTab=ref('游玩'),composeMode=ref('action')
function openCharacter(tab){readingOpen.value=false;window.dispatchEvent(new Event('close-music-panel'));window.dispatchEvent(new CustomEvent('open-character-panel',{detail:{tab}}))}
function openMusic(){readingOpen.value=false;window.dispatchEvent(new Event('close-main-model-panel'));window.dispatchEvent(new Event('open-music-panel'))}
function lightMenu(event){if(event.pointerType!=='mouse' || currentMotion()!=='FULL')return;const el=event.currentTarget;el.style.setProperty('--menu-light-x',`${event.clientX-el.getBoundingClientRect().left}px`)}
const narrowReading=()=>matchMedia('(max-width:760px)').matches
function showPast(){historyOpen.value=true;if(historyError.value && !loadingHistory.value)loadTranscript()}
const worldBookVisible=ref(false),worldBookRefresh=ref(0),bookInitialQuery=ref(null)
function openRequestedBook(event){if(perspectiveSwitching.value || event.detail?.sessionId!==sessionId.value)return;bookInitialQuery.value={keyword:event.detail.keyword || '',roleId:event.detail.roleId || ''};worldBookVisible.value=true}
watch(worldBookVisible,open=>{if(!open)bookInitialQuery.value=null})
onMounted(()=>window.addEventListener('open-world-book',openRequestedBook))
onBeforeUnmount(()=>window.removeEventListener('open-world-book',openRequestedBook))
const composeModes=[{key:'action',label:'推进故事'},{key:'talk',label:'亲自交流'},{key:'move',label:'行路'}]
const atmosphereCache=new Map()
const atmosphere=computed(()=>{const id=scene.value.sceneId;if(!id)return sceneAtmosphere(scene.value);if(!atmosphereCache.has(id))atmosphereCache.set(id,sceneAtmosphere(scene.value));return atmosphereCache.get(id)})
const composeText=computed({get:()=>composeMode.value==='move'?playerMovement.value:playerAction.value,set:value=>{if(composeMode.value==='move')playerMovement.value=value;else playerAction.value=value}})
const railProps=computed(()=>({scene:scene.value,busy:actionBlocked.value,target:knowledgeRecipient.value,selected:selectedCoordKey.value,visited:visitedLocations.value,objectives:sessionStore.objectives,legacyRepair:legacyRepair.value,atmosphere:atmosphere.value}))
function onStoryScroll(){storyReading.onScroll()}
function scrollToLatest(){storyReading.jumpToLatest()}
function focusComposer(){scenePanelOpen.value=false;nextTick(()=>actionInput.value?.focus())}
function draftTalk(person){if(busy.value)return;knowledgeRecipient.value=person.godId;composeMode.value='talk';focusComposer()}
function draftTreaty({targetId,targetName,text}){
 if(actionBlocked.value || !targetId)return
 const present=(scene.value.presentGods || []).find(g=>g.godId===targetId)
 if(present){knowledgeRecipient.value=targetId;composeMode.value='talk';playerAction.value=text}
 else{composeMode.value='action';playerAction.value=`我尝试联系${targetName || '这份盟约的对方'}，向其表达：“${text}”按实际联系经过与对方意愿继续，不视为已经送达或获得回应。`}
 focusComposer()
}
function draftExit(exit){if(busy.value)return;composeMode.value='move';playerMovement.value=`我沿${exit.exitName || directionText(exit.direction)}前行。`;focusComposer()}
function submitComposer(){
 if(actionBlocked.value)return
 if(composeMode.value==='move')return onExplore()
 if(composeMode.value==='talk'){
  const person=(scene.value.presentGods || []).find(g=>g.godId===knowledgeRecipient.value)
  if(!person){actionError.value='请先选择仍在场的交谈对象。';return}
  return onSubmit({targetGodId:person.godId,spokenText:playerAction.value.trim()},person.godName)
 }
 return onSubmit()
}
const loadingHistory=ref(false),historyHasMore=ref(false),historyError=ref(false);let historyCursor=null
let sceneRequestSequence=0,historyRequestSequence=0,syncRequestSequence=0,locationsRequestSequence=0,savePointsRequestSequence=0
async function loadTranscript(older=false){
 if(loadingHistory.value)return
 const sequence=++historyRequestSequence
 const valid=requestIdentity(sessionStore,()=>operationViewAlive && sequence===historyRequestSequence)
 loadingHistory.value=true;historyError.value=false
 const append=older && historyCursor!==null
 let readingSnapshot
 try{const params={sessionId:sessionId.value};if(older && historyCursor)params.beforeId=historyCursor
  const response=await http.get('/game/transcript',{params,timeout:operationRequestTimeout});if(!valid())return;if(response.sessionId!=null && response.sessionId!==sessionId.value || response.branchEpoch!=null && (Number(response.branchEpoch)!==Number(scene.value.branchEpoch) || Number(response.revision)!==Number(scene.value.revision))){historyError.value=true;syncRequired.value=true;return}const saved=(response.turns || []).map(t=>({...t,id:`saved-${t.id}`,actors:t.actors || []}))
  readingSnapshot=storyReading.capture();openingStory.value=response.opening?.text?response.opening:null;turns.value=append?[...saved,...turns.value]:saved;historyHasMore.value=!!response.hasMore;historyCursor=response.nextBeforeId || null
  await storyReading.present(readingSnapshot,{kind:append?'prepend':'restore',valid})
 }catch(e){if(valid())historyError.value=true}finally{if(valid.owned())loadingHistory.value=false}
}

onMounted(()=>{document.body.classList.add('playing-game');window.addEventListener('music-panel-state',musicPanelState)})
onBeforeUnmount(()=>{document.body.classList.remove('playing-game');document.title='此生行记';window.removeEventListener('music-panel-state',musicPanelState)})

// ===== Phase 8 目标状态辅助函数 =====
function objStatusType(status) {
  if (status === 'COMPLETED') return 'success'
  if (status === 'FAILED') return 'danger'
  return 'warning'
}
function objStatusText(status) {
  if (status === 'COMPLETED') return '已成'
  if (status === 'FAILED') return '已败'
  return '进行中'
}

// ===== 6.2 拓扑图：方向文本辅助函数 =====
function directionText(direction) {
  const map = {
    NORTH: '北', SOUTH: '南', EAST: '东', WEST: '西',
    UP: '上', DOWN: '下', OTHER: '其他'
  }
  return map[direction] || direction
}

// ===== Phase 10 NPC 行为辅助函数 =====
function npcBehaviorTagType(behavior) {
  if (!behavior) return 'info'
  const b = behavior.toUpperCase()
  if (b === 'ATTACK' || b === 'OPPOSE') return 'danger'
  if (b === 'SUPPORT') return 'success'
  if (b === 'RESPOND' || b === 'INQUIRE') return 'warning'
  if (b === 'LEAVE') return 'info'
  return 'info'
}
function npcBehaviorText(behavior) {
  const map = {
    RESPOND: '回应',
    ATTACK: '攻击',
    SUPPORT: '援助',
    OPPOSE: '反对',
    IGNORE: '无视',
    LEAVE: '离去',
    INQUIRE: '询问'
  }
  if (!behavior) return '观望'
  return map[behavior.toUpperCase()] || behavior
}

async function loadScene() {
  const sequence=++sceneRequestSequence
  const modeAtRead=view.mode
  const owner=requestIdentity(sessionStore,()=>operationViewAlive && sequence===sceneRequestSequence,{allowBranchChange:true})
  const valid=()=>owner() && modeAtRead===view.mode
  loadingScene.value=true
  sceneLoadError.value=false
  try {
    const r = await http.get('/game/scene', {
      params: { playerId: playerId.value, sessionId: sessionId.value }, timeout: operationRequestTimeout
    })
    if(!valid())return
    // 6.2 拓扑图：加载当前场景的已知出口
    try {
      const exitsResp = await http.get('/game/exits', {
        params: { sessionId: sessionId.value, sceneId: r.sceneId }, timeout: operationRequestTimeout
      })
      r.exits = exitsResp.exits || []
    } catch (e) {
      r.exits = []
    }
    if(!valid())return
    if(!applyScene(r))return
    sessionStore.setPendingOperation(reconcilePendingOperation(r,sessionStore.pendingOperation,sessionId.value))
    if(!(r.presentGods || []).some(g=>g.godId===knowledgeRecipient.value))knowledgeRecipient.value=''
    window.dispatchEvent(new Event('character-state-changed'))
    if (r.playerGodId) {godNameMap.value[r.playerGodId]="你";sessionStore.setActiveSession({playerGodId:r.playerGodId,sceneId:r.sceneId})}
    if (r.presentGods) {
      r.presentGods.forEach(g => {
        if (typeof g === 'object') godNameMap.value[g.godId] = g.godName
      })
    }
    // Phase 8：describeScene 返回 objectives，同步到 store
    if (r.objectives) {
      sessionStore.setObjectives(r.objectives)
    }
  } catch (e) {
    if(valid()){sceneLoadError.value=true;handleErr(e)}
  } finally {if(owner.owned())loadingScene.value=false}
}

function applyScene(r){
 if(scene.value.branchEpoch!=null && r.branchEpoch!=null && (Number(r.branchEpoch)<Number(scene.value.branchEpoch) || Number(r.branchEpoch)===Number(scene.value.branchEpoch) && Number(r.revision)<Number(scene.value.revision)))return false
 scene.value=r
 if(['DREAMER','OMNISCIENT'].includes(r.viewMode))view.setMode(r.viewMode)
 sessionStore.setActiveSession({branchEpoch:r.branchEpoch ?? null,revision:r.revision ?? 0,sceneId:r.sceneId})
 if(r.playerGodId){godNameMap.value[r.playerGodId]='你';sessionStore.setActiveSession({playerGodId:r.playerGodId})}
 for(const person of r.presentGods || [])godNameMap.value[person.godId]=person.godName
 if(r.objectives)sessionStore.setObjectives(r.objectives)
 if(r.sessionStatus){
  sessionStore.setActiveSession({status:r.sessionStatus})
  if(['DEAD','RETIRED','VICTORY'].includes(r.sessionStatus)){
   deathSummary.value=r.endingSummary || r.deathSummary || ''
   finalEvaluation.value=r.finalEvaluation || ''
   deathDialogVisible.value=!r.pendingOperationId
  } else {
   deathDialogVisible.value=false;deathSummary.value='';finalEvaluation.value=''
  }
 }
 if(!(r.presentGods || []).some(g=>g.godId===knowledgeRecipient.value))knowledgeRecipient.value=''
 window.dispatchEvent(new Event('character-state-changed'))
 return true
}

async function resyncCurrentBranch(){
 syncRequired.value=true
 const sequence=++syncRequestSequence
 ++sceneRequestSequence;++historyRequestSequence
 const valid=requestIdentity(sessionStore,()=>operationViewAlive && sequence===syncRequestSequence,{allowBranchChange:true})
 loadingScene.value=true;loadingHistory.value=true;sceneLoadError.value=false;historyError.value=false
 try{
  const params={playerId:playerId.value,sessionId:sessionId.value}
  let current,history,consistent=false
  // A background passage can commit between these reads. Retry only the free projections;
  // never restart an action or grant additional model calls to synchronize a page.
  for(let attempt=0;attempt<3;attempt++){
   current=await http.get('/game/scene',{params,timeout:operationRequestTimeout});if(!valid())return
   history=await http.get('/game/transcript',{params:{sessionId:params.sessionId},timeout:operationRequestTimeout});if(!valid())return
   consistent=(history.sessionId==null || history.sessionId===params.sessionId) && Number(history.branchEpoch)===Number(current.branchEpoch) && Number(history.revision)===Number(current.revision)
   if(consistent)break
  }
  if(!consistent){historyError.value=true;return false}
  try{const exits=await http.get('/game/exits',{params:{sessionId:params.sessionId,sceneId:current.sceneId},timeout:operationRequestTimeout});current.exits=exits.exits || []}catch{current.exits=[]}
  if(!valid())return
  if(scene.value.branchEpoch!=null && (Number(current.branchEpoch)<Number(scene.value.branchEpoch) || Number(current.branchEpoch)===Number(scene.value.branchEpoch) && Number(current.revision)<Number(scene.value.revision))){historyError.value=true;return}
  // Apply the scene and latest chapter together only after their server versions agree.
  const readingSnapshot=storyReading.capture()
  applyScene(current)
  openingStory.value=history.opening?.text?history.opening:null
  turns.value=(history.turns || []).map(t=>({...t,id:`saved-${t.id}`,actors:t.actors || []}))
  historyHasMore.value=!!history.hasMore;historyCursor=history.nextBeforeId || null
  sessionStore.setPendingOperation(reconcilePendingOperation(current,sessionStore.pendingOperation,sessionId.value))
  syncRequired.value=false
  actionError.value=''
  await storyReading.present(readingSnapshot,{kind:'restore',valid})
  return true
 }catch(e){if(valid()){sceneLoadError.value=true;handleErr(e)}}
 finally{if(valid.owned()){loadingScene.value=false;loadingHistory.value=false}}
 return false
}
function applyOperation(r,sid){
 if(r.status==='STALE_BRANCH'){
  if(sid!==sessionId.value || sessionStore.pendingOperation?.operationId!==r.operationId)return false
  syncRequired.value=true
  if(!playerAction.value.trim())playerAction.value=sessionStore.pendingOperation.playerInput || ''
  sessionStore.setPendingOperation(null);clearTimeout(operationPoll)
  resyncCurrentBranch();return false
 }
 if(sessionStore.pendingOperation && sessionStore.pendingOperation.operationId!==r.operationId)return false
 if(!canApplyOperation(r,scene.value,sessionId.value,sid)){actionError.value='收到的结果属于旧分支或旧版本，未覆盖当前现场。请重新读取。';return false}
 const acceptedInput=r.playerInput ?? sessionStore.pendingOperation?.playerInput
 if(['RUNNING','PARTIAL','COMPLETED'].includes(r.status) && acceptedInput && playerAction.value.trim()===acceptedInput)playerAction.value=''
 const readingSnapshot=storyReading.capture()
 const mergedTurns=mergeOperationChapter(turns.value,r,sessionStore.pendingOperation?.playerInput || '')
 const chapterChanged=mergedTurns!==turns.value
 turns.value=mergedTurns
 const previousSceneId=scene.value.sceneId
 applyScene(r.scene)
 if(previousSceneId!==r.scene.sceneId)loadVisitedLocations()
 dialogueNotice.value=operationNotice(r.status,r.pendingKind)
 if(['RUNNING','PARTIAL'].includes(r.status))sessionStore.setPendingOperation({operationId:r.operationId,branchEpoch:r.branchEpoch,status:r.status,playerInput:r.playerInput,pendingKind:r.pendingKind,inputReview:r.inputReview,technicalIssues:r.ruleSnapshot?.technicalIssues || [],startedAt:sessionStore.pendingOperation?.startedAt || Date.now()})
 else sessionStore.setPendingOperation(null)
 if(r.status==='COMPLETED')Promise.all([loadMemory(),loadNpcDecisions()])
 actionError.value=''
 if(chapterChanged)void storyReading.present(readingSnapshot,{valid:()=>operationViewAlive && sessionId.value===sid})
 return true
}

async function operatePending(action='read',reviewChoice=null){
 if(busy.value || queryingOperation.value || !pendingOperation.value)return
 const reading=action==='read'
 const operation={...pendingOperation.value},sid=sessionId.value,modeAtRead=view.mode
 const owner=requestIdentity(sessionStore,()=>operationViewAlive)
 const valid=()=>owner() && modeAtRead===view.mode
 if(reading)queryingOperation.value=true;else loading.value=true
 if(!reading)actionError.value=''
 if(action==='recover'){sessionStore.setPendingOperation({...operation,startedAt:Date.now()});requestClockKey=null}
 try{
  const path=`/game/operations/${encodeURIComponent(operation.operationId)}`
  const params={sessionId:sid,branchEpoch:operation.branchEpoch}
  const r=action==='read'?await http.get(path,{params,timeout:operationRequestTimeout}):await http.post(`${path}/${action}`,reviewChoice?{reviewId:operation.inputReview?.reviewId,choice:reviewChoice}:null,{params,timeout:operationRequestTimeout})
  if(!valid())return
  operationPollFailures=0
  applyOperation(r,sid)
 }catch(e){
  if(!valid() || sessionStore.pendingOperation?.operationId!==operation.operationId)return
  if(action==='read')operationPollFailures++
  if(e.response?.data?.code===200010){
   if(replayableSubmission(operation,sid,scene.value.branchEpoch)){
    sessionStore.setPendingOperation({...operation,status:'PARTIAL',pendingKind:'SUBMISSION_NOT_REGISTERED'});actionError.value=''
   }else{if(!playerAction.value.trim())playerAction.value=operation.playerInput || '';sessionStore.setPendingOperation(null);dialogueNotice.value='服务器未找到这笔旧操作，原输入已保留，请结合当前进展确认后再提交。';actionError.value=''}
  }
  else actionError.value=e.response?.data?.message || '未能取得操作结果。原操作标识已保留，可再次查询。'
 }
 finally{if(owner.owned()){if(reading)queryingOperation.value=false;else loading.value=false}}
}
function reviewInputMeaning(choice){return operatePending('input-review',choice)}
async function editReviewedInput(){
 const valid=requestIdentity(sessionStore,()=>operationViewAlive)
 const original=pendingOperation.value?.playerInput || ''
 await operatePending('cancel')
 if(valid() && !pendingOperation.value){if(!playerAction.value.trim())playerAction.value=original;composeMode.value='action';focusComposer()}
}
function queryOperation(){return operatePending()}
async function resumeOperation(){
 const request=pendingOperation.value?.pendingKind==='SUBMISSION_NOT_REGISTERED'
  ?replayableSubmission(pendingOperation.value,sessionId.value,scene.value.branchEpoch):null
 if(!request)return operatePending('recover')
 if(busy.value || queryingOperation.value)return
 const valid=requestIdentity(sessionStore,()=>operationViewAlive)
 loading.value=true;actionError.value=''
 try{await submitOperation(null,request.playerText,request)}finally{if(valid.owned())loading.value=false}
}
function cancelOperation(){return operatePending('cancel')}

async function submitOperation(dialogue,submittedAction,originalRequest=null){
 const valid=requestIdentity(sessionStore,()=>operationViewAlive)
 const request=originalRequest || {operationId:crypto.randomUUID(),sessionId:sessionId.value,sceneId:scene.value.sceneId,playerText:submittedAction,
  ...(dialogue?{dialogue:{...dialogue}}:{}),storyMode:!dialogue,narrativeMode:readingPreferences.value.narrativeMode,branchEpoch:scene.value.branchEpoch ?? 0,expectedRevision:scene.value.revision ?? 0}
 const {sessionId:sid,operationId,branchEpoch}=request
 operationPollFailures=0
 sessionStore.setPendingOperation({operationId,branchEpoch,status:'RUNNING',playerInput:submittedAction,startedAt:Date.now(),submission:request})
 try{
  const r=await http.post('/game/operations',request,{timeout:operationRequestTimeout})
  if(!valid())return
  if(applyOperation(r,sid) && playerAction.value.trim()===submittedAction)playerAction.value=''
 }catch(e){
  if(!valid())return
  // A network failure leaves the durable identifier intact. A rejected request has no accepted operation.
  if(e.response?.data?.code===200009){
   sessionStore.setPendingOperation(null)
   // The server may have advanced or accepted an action in another tab. Restore
   // its saved chapter and pending ID together, without resubmitting this input.
   const synchronized=await resyncCurrentBranch()
   if(valid.owned() && synchronized)dialogueNotice.value='已读取当前存档的最新进展。你的输入仍保留，请结合新进展确认后再提交。'
  }
  else if(e.response && [100002,600003].includes(e.response.data?.code)){sessionStore.setPendingOperation(null);actionError.value=e.response.data.message}
  else{sessionStore.setPendingOperation({...sessionStore.pendingOperation,status:'UNKNOWN'});actionError.value='未收到完整结果。请查询这一操作；输入和操作标识已保留。'}
 }
}

// Phase 8：独立加载目标列表（动作后刷新用）
async function loadLegacyRepair() {
 const sequence=++legacyRepairSequence
 const valid=requestIdentity(sessionStore,()=>operationViewAlive && sequence===legacyRepairSequence)
 legacyRepair.value={};legacyRepairDialog.value=false
 if(!scene.value.sceneId || pendingOperation.value || sessionStore.status!=='PLAYING')return
 try{const r=await http.get('/game/objectives/legacy-repair',{params:{sessionId:sessionId.value}});if(valid())legacyRepair.value=r || {}}
 catch{ /* A failed compatibility check must not block normal play. */ }
}
async function showLegacyRepair(){
 if(actionBlocked.value)return
 const valid=requestIdentity(sessionStore,()=>operationViewAlive)
 await loadLegacyRepair()
 if(valid() && legacyRepair.value.available)legacyRepairDialog.value=true
}
async function applyLegacyRepair(){
 if(actionBlocked.value || !legacyRepair.value.available)return
 const valid=requestIdentity(sessionStore,()=>operationViewAlive)
 const proposal=legacyRepair.value,sid=sessionId.value
 loading.value=true
 try{
  await http.post('/game/objectives/legacy-repair',{sessionId:sid,branchEpoch:proposal.branchEpoch,expectedRevision:proposal.revision,previewToken:proposal.previewToken})
  if(!valid())return
  legacyRepairDialog.value=false
  await resyncCurrentBranch()
 }catch(e){if(valid()){handleErr(e);await resyncCurrentBranch();await loadLegacyRepair()}}
 finally{if(valid.owned())loading.value=false}
}

async function loadObjectives() {
  const valid=requestIdentity(sessionStore,()=>operationViewAlive)
  try {
    const r = await http.get('/game/objectives', {
      params: { playerId: playerId.value, sessionId: sessionId.value }
    })
    if(valid())sessionStore.setObjectives(r.objectives || [])
  } catch (e) {
    console.warn('[loadObjectives]', e)
  }
}

// Phase 9：加载记忆上下文（天眼面板用）
async function loadMemory() {
  if(!isOmniscient.value || perspectiveSwitching.value){sessionStore.setMemoryContext('');return}
  const mode=view.mode
  const valid=requestIdentity(sessionStore,()=>operationViewAlive && view.mode===mode && !perspectiveSwitching.value)
  try {
    const r = await http.get('/game/memory', {
      params: { playerId: playerId.value, sessionId: sessionId.value }
    })
    if(valid())sessionStore.setMemoryContext(r.memoryContext || '')
  } catch (e) {
    console.warn('[loadMemory]', e)
  }
}

// Phase 10：加载 NPC 决策追溯历史（天眼面板群戏追溯用）
async function loadNpcDecisions() {
  if(!isOmniscient.value || perspectiveSwitching.value){sessionStore.setNpcDecisionHistory([]);return}
  const mode=view.mode
  const valid=requestIdentity(sessionStore,()=>operationViewAlive && view.mode===mode && !perspectiveSwitching.value)
  try {
    const r = await http.get('/game/npc-decisions', {
      params: { sessionId: sessionId.value }
    })
    if(!valid())return
    ;(r.decisions || []).forEach(d=>{if(d.godName)godNameMap.value[d.godId]=d.godName});
    sessionStore.setNpcDecisionHistory(r.decisions || [])
  } catch (e) {
    console.warn('[loadNpcDecisions]', e)
  }
}

async function onSubmit(dialogue=null,recipientName='') {
  if (!playerAction.value.trim() || actionBlocked.value || !scene.value.sceneId || sceneLoadError.value || sessionStore.status!=='PLAYING') return
  const submittedAction=playerAction.value.trim()
  actionError.value='';dialogueNotice.value='';pendingRecipient.value=recipientName;loading.value=true
  const valid=requestIdentity(sessionStore,()=>operationViewAlive)
  try{await submitOperation(dialogue,submittedAction)}finally{if(valid.owned())loading.value=false}
}

// Step 5：死亡检查 → POST /game/death-check
async function checkDeath() {
  try {
    const r = await http.post('/game/death-check', {
      sessionId: sessionId.value,
      playerId: playerId.value
    })
    if (r.isDead) {
      deathDialogVisible.value = true
      await processDeath()
    }
  } catch (e) {
    // 死亡检查失败不影响游戏继续
    console.warn('[checkDeath]', e)
  }
}

// Step 5：死亡总结 → POST /game/death
async function processDeath() {
  try {
    const r = await http.post('/game/death', {
      sessionId: sessionId.value,
      playerId: playerId.value
    })
    deathSummary.value = r.deathSummary || '（死亡总结生成失败）'
    finalEvaluation.value = r.finalEvaluation || ''

    // Phase 8：调 /game/ending 做多结局判定（playerDead=true 走 DEFEAT 分支）
    try {
      const ending = await http.get('/game/ending', {
        params: {
          sessionId: sessionId.value,
          playerId: playerId.value,
          playerDead: true
        }
      })
      if (ending.description) {
        finalEvaluation.value = (finalEvaluation.value ? finalEvaluation.value + '\n\n' : '')
          + `【结局判定：${ending.ending}】${ending.description}`
      }
    } catch (e) {
      console.warn('[ending]', e)
    }

    // 标记当前会话为 DEAD
    sessionStore.setActiveSession({ status: 'DEAD' })
  } catch (e) {
    handleErr(e)
  }
}

// Step 5：重开 → POST /game/restart
async function restartGame(inheritWorld) {
  if(busy.value || pendingOperation.value) return
  const valid=requestIdentity(sessionStore,()=>operationViewAlive)
  const previousSession=sessionId.value
  restarting.value = true
  try {
    const r = await http.post('/game/restart', {
      playerId: playerId.value,
      viewMode: view.mode,
      inheritWorld,
      prevSessionId: previousSession
    })
    if(!valid())return
    const accountId=playerId.value
    sessionStore.clearSession()
    // 写入新会话状态，重定向到角色创建
    sessionStore.setActiveSession({
      playerId: accountId,
      sessionId: r.newSessionId,
      sceneId: null,
      playerGodId: null,
      status: 'CREATING',
      inheritWorld: r.inheritedWorld,
      prevSessionId: previousSession
    })
    deathDialogVisible.value = false
    router.push('/new-game')
  } catch (e) {
    if(valid())handleErr(e)
  } finally {
    restarting.value = false
  }
}

// ===== 6.4 事件溯源：时空回溯 =====
watch(rewindDialogVisible, (v) => {
  if (v) loadSavePoints()
})

async function loadSavePoints() {
  const sequence=++savePointsRequestSequence
  const valid=requestIdentity(sessionStore,()=>operationViewAlive && sequence===savePointsRequestSequence)
  try {
    const r = await http.get('/game/save-points', { params: { sessionId: sessionId.value } })
    if(valid())savePoints.value = r
  } catch (e) {
    if(valid())handleErr(e)
  }
}

async function loadToRound(targetRound) {
  if (busy.value || syncRequired.value) return
  const valid=requestIdentity(sessionStore,()=>operationViewAlive,{allowBranchChange:true})
  rewinding.value = true
  syncRequired.value = true
  try {
    await http.post('/game/load-to-round', {
      sessionId: sessionId.value,
      targetRound: targetRound
    })
    if(!valid())return
    rewindDialogVisible.value=false
    clearTimeout(operationPoll)
    sessionStore.setPendingOperation(null)
    // The rewind is committed. Keep input blocked until scene and transcript agree on the new branch.
    const synchronized=await resyncCurrentBranch()
    if(!valid())return
    if(!synchronized){actionError.value='服务器已完成回溯，当前进展尚未完整读回。请重新读取当前进展，无需再次回溯。';return}
    sessionStore.setLastNpcDecisions([])
    await Promise.all([loadMemory(),loadNpcDecisions(),loadVisitedLocations(),loadSavePoints()])
    if(!valid())return
    actionError.value=''
    ElMessage.success(`已回溯到第 ${targetRound} 回合`)
    rewindDialogVisible.value = false
  } catch (e) {
    if(valid()){
      rewindDialogVisible.value=false
      actionError.value='未收到完整的回溯结果。请重新读取当前进展，确认服务器状态后再行动，无需重复回溯。'
      handleErr(e)
    }
  } finally {
    if(valid.owned())rewinding.value = false
  }
}

// 新增：自由文本驱动移动 → POST /game/explore
async function onExplore() {
  if(!playerMovement.value.trim() || actionBlocked.value || sessionStore.status!=='PLAYING')return
  const input=playerMovement.value.trim();loading.value=true;pendingRecipient.value=''
  try{await submitOperation(null,input);if(!actionError.value && playerMovement.value.trim()===input)playerMovement.value=''}finally{loading.value=false}
}


async function onMoveCoord() {
 if(actionBlocked.value || sessionStore.status!=='PLAYING')return
 const loc=visitedLocations.value.find(l=>l.sceneId===selectedCoordKey.value)
 if(!loc?.sceneId){actionError.value='请先选择已亲历的地点。';return}
 const input=`沿已知路线回到${loc.locationName}。`;movingCoord.value=true
 try{await submitOperation(null,input)}finally{movingCoord.value=false}
}

// Step 4：加载已访问地点列表（替代手输 x,y）
async function loadVisitedLocations() {
  const sequence=++locationsRequestSequence
  const valid=requestIdentity(sessionStore,()=>operationViewAlive && sequence===locationsRequestSequence)
  try {
    const r = await http.get('/game/world-book', { params: { sessionId: sessionId.value } })
    if(!valid())return
    visitedLocations.value = r.visitedLocations || []
    if(!visitedLocations.value.some(location=>location.sceneId===selectedCoordKey.value))selectedCoordKey.value=''
  } catch (e) {
    // 静默失败，不打扰玩家
    console.warn('[loadVisitedLocations]', e)
  }
}

// Step 4：打听机制 → POST /game/inquire
async function onInquire() {
 if(actionBlocked.value || sessionStore.status!=='PLAYING')return
 const loc=visitedLocations.value.find(l=>l.sceneId===selectedCoordKey.value)
 if(!loc){actionError.value='请先选择要打听的已知地点。';return}
 const person=(scene.value.presentGods || []).find(g=>g.godId===knowledgeRecipient.value)
 const question=`你了解${loc.locationName}最近的情况吗？`
 if(!person){composeMode.value='action';playerAction.value=`我尝试打听${loc.locationName}最近的情况，寻找可以接触的知情者，按其实际所知自然询问，不把传言当作已经证实。`;actionError.value='';focusComposer();return}
 inquiring.value=true
 try{await submitOperation({targetGodId:person.godId,spokenText:question},question)}finally{inquiring.value=false}
}

function addMoveTurn(r, label) {
 const readingSnapshot=storyReading.capture()
  const actors = (r.lines || []).map(l => ({
    godId: l.godId,
    godName: actorName(l.godId,godNameMap.value),
    displayedDialogue: l.speech || '',
    truePsychologicalActivity: l.innerThought || '',
    isLying: r.lieFlags?.[l.godId] || false
  }))
  turns.value.push({
      id:nextTurnId++,
    playerInput: label,
    actors,
    outcomeLabel: r.outcomeLabel || '',
    facts: r.factsChinese || []
  })
 void storyReading.present(readingSnapshot,{valid:()=>operationViewAlive})
}

function handleErr(e) {
  const msg = e.response?.data?.message || e.message || '请求失败'
  ElMessage.error(msg)
}

onMounted(async () => {
  const valid=requestIdentity(sessionStore,()=>operationViewAlive,{allowBranchChange:true})
  sessionStore.restorePendingOperation()
  // 守卫已保证 canPlay，但若直接访问兜底
  if (!sessionStore.canPlay) {
    router.push('/home')
    return
  }
  await resyncCurrentBranch()
  if(!valid())return
  if(pendingOperation.value)await queryOperation()
  if(!valid())return
  await loadVisitedLocations()
  if(!valid())return
  // Phase 9：初始加载记忆上下文（天眼面板用）
  await loadMemory()
  if(!valid())return
  // Phase 10：初始加载 NPC 决策追溯历史（断线重连保留追溯）
  await loadNpcDecisions()
  if(!valid())return
  if(['DEAD','RETIRED'].includes(sessionStore.status) && !pendingOperation.value) {
    const ownsEnding=requestIdentity(sessionStore,()=>operationViewAlive)
    try {
      const status=await http.get('/game/session-status',{params:{sessionId:sessionId.value}})
      if(!ownsEnding())return
      deathSummary.value=status.endingSummary || status.deathSummary || ''
      finalEvaluation.value=status.finalEvaluation || ''
      deathDialogVisible.value=true
    } catch(e) {
      if(ownsEnding())actionError.value='结局资料暂未读取成功，可稍后重新打开存档查看。'
    }
  } else if(sessionStore.status==='VICTORY') {
    deathSummary.value='这是旧版本已保存的通关记录。'
    finalEvaluation.value='可以回溯时空或重新化生'
    deathDialogVisible.value=true
  }
})


const activeNavigation=computed(()=>navigationSelection({characterOpen:characterPanelPresentation.open,characterTab:characterPanelPresentation.tab,book:worldBookVisible.value,history:historyOpen.value,info:infoVisible.value,infoTab:infoTab.value,formula:formulaDialogVisible.value,scene:scenePanelOpen.value,other:perspectiveVisible.value || rewindDialogVisible.value || deathDialogVisible.value}))
const railPanelRefs=[historyOpen,worldBookVisible,infoVisible,scenePanelOpen,formulaDialogVisible,perspectiveVisible,rewindDialogVisible,deathDialogVisible]
function closeRailPanels(except=-1){railPanelRefs.forEach((panel,index)=>{if(index!==except)panel.value=false});if(except!==railPanelRefs.length)window.dispatchEvent(new Event('close-character-panel'))}
function returnToStory(){const previous=activeNavigation.value;closeRailPanels();if(previous==='story')scrollToLatest();else nextTick(()=>storyViewport.value?.focus({preventScroll:true}))}
watch(()=>[...railPanelRefs.map(panel=>panel.value),characterPanelPresentation.open],(values,previous=[])=>{const opened=values.map((open,index)=>open && !previous[index]?index:-1).filter(index=>index>=0);if(!opened.length)return;closeRailPanels(opened.at(-1));readingOpen.value=false;window.dispatchEvent(new Event('close-music-panel'))},{flush:'sync',immediate:true})
onBeforeUnmount(()=>window.dispatchEvent(new Event('close-character-panel')))
</script>
