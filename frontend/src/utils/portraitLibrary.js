import {portraitCatalog,portraitTypes,portraitAssetSource} from './characterPortrait'
import {commonPortraits} from './portraitFallback'

const names={WESTERN:'西幻',EASTERN:'中幻',MODERN:'现代',female:'女性',male:'男性',unknown:'未限定',YOUNG:'青年',ADULT:'成年',ELDER:'老年',CHILD:'儿童',human:'人类',elf:'精灵',dragonborn:'龙裔',humanoid:'类人',vampire:'吸血鬼',divine:'神性形象',
 NOBLE:'贵族',DIPLOMAT:'外交官',SOCIALITE:'名媛',MAGE:'法师',SCHOLAR:'学者',ACADEMY_MAGE:'学院法师',KNIGHT:'骑士',WARRIOR:'战士',GUARD:'守卫',ATTENDANT:'侍从',HOUSEKEEPER:'管家',ELF:'精灵旅人',ROSE_ELF:'玫瑰精灵',ENVOY:'使者',RANGER:'游侠',SCOUT:'斥候',PALADIN:'圣骑士',MERCENARY:'佣兵',COMMANDER:'指挥官',THIEF:'盗贼',ROGUE:'游荡者',PRIESTESS:'女祭司',PRIEST:'祭司',CLERIC:'神职者',DRAGON_RIDER:'龙骑士',DRAGONBORN:'龙裔',SWORDSWOMAN:'女剑士',NUN_KNIGHT:'修女骑士',DUAL_SWORD_KNIGHT:'双剑骑士',UNICORN_CAVALIER:'独角兽骑士',CAVALRY:'骑兵',WITCH:'女巫',DIVINER:'占卜师',STORM_MAGE:'风暴法师',BARD:'吟游诗人',MUSICIAN:'乐师',SINGER:'歌者',ALCHEMIST:'炼金术师',ARTIFICER:'工匠',MECHANIC:'机械师',ENGINEER:'工程师',SMITH:'铁匠',DARK_KNIGHT:'黑骑士',APOCALYPSE_WARRIOR:'末世战士',JUDGE:'审判者',HERALD:'传令官',NECROMANCER:'死灵法师',VAMPIRE:'吸血鬼',PIRATE_CAPTAIN:'海盗船长',PIRATE:'海盗',CAPTAIN:'船长',SWORDSMAN:'剑客',CRIMSON_GOWN:'酒红礼服'}
export const portraitTagName=value=>names[value] || value
const unique=values=>[...new Set(values.filter(Boolean))]
const detailNames={species:'种类',coat:'毛色',mane:'鬃毛',horn:'独角',armor:'装甲',leftHand:'左手',rightHand:'右手',leftForearm:'左前臂',unicorn:'独角兽','pure-white':'纯白','silver-white':'银白',pearl:'珍珠色','lavender-silver':'淡紫与银色','dapple-gray':'灰斑',charcoal:'炭黑',silver:'银色','white-blue':'白蓝配色','rubber glove':'橡胶手套',bare:'未戴手套','brass-steel mechanical prosthesis':'黄铜与钢制机械义肢','brass-blacksteel mechanical prosthesis':'黄铜与黑钢机械义肢',natural:'自然肢体'}
const list=value=>Array.isArray(value)?value.flatMap(list):value && typeof value==='object'?Object.entries(value).map(([key,part])=>`${detailNames[key] || key}：${list(part).map(text=>detailNames[text] || text).join('、')}`):value?[String(value)]:[]

function entry(asset){
 const cultures=asset.cultures || ['WESTERN'],roles=asset.roles || list(asset.role)
 const tags=unique([...cultures,asset.gender,...(asset.ages || []),asset.species,...roles,asset.style].map(portraitTagName))
 const groups=[['发色与发型',asset.hair],['眼睛',asset.eyes],['衣装',asset.outfit],['装备与配饰',asset.equipment],['特征与关键词',asset.keywords],['匹配职业',asset.selection?.roles?.map(portraitTagName)],['匹配关键词',asset.selection?.keywords],['避用描述',asset.appearanceExclusions],['坐骑',asset.mount],['体态',asset.anatomy],['必要义肢',asset.requiredProsthesis],['适用种族',asset.allowedSpecies?.map(portraitTagName)]]
  .map(([label,values])=>({label,values:unique(list(values))})).filter(group=>group.values.length)
 return {...asset,label:asset.label || portraitTypes[asset.key] || asset.key,src:portraitAssetSource(asset),cultures,roles,tags,groups}
}

const eastern=[
 ['young-woman-a','female',['YOUNG'],'青衣'],['young-woman-b','female',['YOUNG'],'朱衣'],['woman','female',['ADULT']],['elder','male',['ELDER']],['elder-woman','female',['ELDER']],['deity','unknown',[]],['child-girl','female',['CHILD']],['man','male',['ADULT']]
].map(([key,gender,ages,keyword])=>({key:`paper-${key}`,label:portraitTypes[key],src:`/art/paper-theatre-v1/${key==='woman'?'woman-v2':key}.png`,gender,ages,species:key==='deity'?'divine':'human',cultures:['EASTERN'],keywords:keyword?[keyword]:[],edition:'基础立绘'}))
const revisedEastern=[['eastern-woman','female','成年女子'],['eastern-man','male','成年男子']].map(([key,gender,label])=>({key,label,src:`/art/refraction-v1/${key}.png`,gender,ages:['ADULT'],species:'human',cultures:['EASTERN'],edition:'基础立绘'}))
const archived=portraitCatalog.filter(asset=>!asset.approved && asset.key!=='western-warrior-female' && !['western-crimson-gown-female','western-rose-elf-female'].includes(asset.key))
 .map(asset=>({...asset,key:`${asset.key}-first-edition`,label:`${portraitTypes[asset.key]} · 初版`,src:`/art/refraction-v1/${asset.key}.png`,archived:true}))
archived.push({key:'paper-woman-first-edition',label:'成年女子 · 初版',src:'/art/paper-theatre-v1/woman.png',gender:'female',ages:['ADULT'],species:'human',cultures:['EASTERN'],archived:true})

// The matching catalogue supplies current artwork; historical files remain viewable as editions.
export const portraitLibrary=[...portraitCatalog.filter(a=>a.approved),...portraitCatalog.filter(a=>!a.approved),...revisedEastern,...eastern,...archived,...commonPortraits].map(entry)
export function filterPortraitLibrary({query='',culture='',gender=''}={}){
 const terms=query.trim().toLocaleLowerCase().split(/\s+/).filter(Boolean)
 return portraitLibrary.filter(asset=>(!culture || asset.cultures.includes(culture)) && (!gender || asset.gender===gender) && terms.every(term=>[asset.key,asset.label,...asset.tags,...asset.groups.flatMap(g=>g.values)].join(' ').toLocaleLowerCase().includes(term)))
}
