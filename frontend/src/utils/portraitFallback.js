// Presentation art only. These anonymous figures do not establish a character's
// face, equipment, ancestry, sex or actions, and are never sent to the story API.
const cache=new Map()
const hash=value=>Array.from(String(value)).reduce((n,c)=>(Math.imul(n,31)+c.codePointAt(0))>>>0,0)
const cultureOf=value=>({东方:'EASTERN',西方:'WESTERN',现代:'MODERN',科幻:'SCIFI'}[value] || String(value || '').toUpperCase())
const poses=['STILL','TURN','FOLDED']
export const commonPortraits=[
 {key:'COMMON-01',label:'未名旅人 · 静候',src:'/art/portraits-common-v1/traveler-still.png',ages:['YOUNG','ADULT','ELDER'],keywords:['通用剪影','匿名','静候','正身','亚麻','披布','灰蓝','逆光','无武器','资料未明','中性留影','透明背景']},
 {key:'COMMON-02',label:'未名旅人 · 回身',src:'/art/portraits-common-v1/traveler-turn.png',ages:['YOUNG','ADULT','ELDER'],keywords:['通用剪影','匿名','回身','侧立','长衣','风动衣摆','灰蓝','逆光','无武器','资料未明','中性留影','透明背景']},
 {key:'COMMON-03',label:'未名少年 · 望远',src:'/art/portraits-common-v1/traveler-child.png',ages:['CHILD'],keywords:['通用剪影','匿名','少年','儿童','望远','短披布','灰蓝','逆光','无武器','资料未明','中性留影','透明背景']}
].map(asset=>({...asset,gender:'unknown',species:'unknown',cultures:['WESTERN','EASTERN','MODERN','SCIFI','MIXED'],edition:'通用剪影',symbolic:true}))

function figure(culture,pose,child){
 const modern=['MODERN','SCIFI'].includes(culture),eastern=culture==='EASTERN'
 const head=child?'translate(-35 26) scale(1.1)':'translate(0 0)'
 const turn=pose==='TURN',folded=pose==='FOLDED'
 const cloak=modern
  ?'M285 294 225 322 167 386 113 1005 181 1078 548 1078 604 1004 552 390 491 323 430 294Z'
  :eastern?'M288 294C229 310 190 352 168 411L72 908 157 1008 205 889 184 1078H565L535 871 585 997 657 906 549 410C526 348 479 307 428 294Z'
  :'M288 291C206 312 173 382 144 483L61 1033 210 1080H551L648 1034 568 485C544 384 498 314 430 291Z'
 const arm=folded
  ?'M223 388C250 422 279 464 301 491L462 457 487 489 308 548C276 548 242 507 206 472Z'
  :turn?'M472 365C451 412 426 461 403 510L309 539 319 575 435 552C468 509 501 460 515 407Z'
  :'M224 378C204 447 183 527 176 620L210 645 238 546 274 439Z'
 const hand=folded?'M451 457 479 451 503 467 499 486 476 490Z':turn?'M312 538 290 542 280 561 294 578 322 573Z':'M176 615 165 640 175 668 196 669 211 641Z'
 const collar=modern
  ?'<path d="m286 302 58 114-62 62-45-139m192-38-57 115 60 61 47-140" fill="#405570" stroke="#d3dce5" stroke-opacity=".5"/><path d="m344 416 16 643 13-643" fill="#152338"/>'
  :eastern?'<path d="m283 303 134 167 66-143-54-27-67 111-49-112Z" fill="#c5d0dc"/><path d="m315 304 89 132-40 38-84-158m124 120 42-106" fill="none" stroke="#e9e9e0" stroke-width="3"/><path d="m215 603 307-8-8 42-298 6Z" fill="#213a56"/><path d="m378 630-30 325 51-13 14-307" fill="#8296ac"/>'
  :'<path d="M230 336c51 83 104 138 132 154 40-30 82-85 130-151-43 16-84 21-128 17-44 4-88-4-134-20Z" fill="#7389a2"/><path d="M233 338c45 51 88 82 130 86 41-2 87-36 126-82M249 362c42 56 80 83 113 89 37-7 71-36 110-87" fill="none" stroke="#d1dbe2" stroke-opacity=".55" stroke-width="2"/><path d="m354 425 8 30 9-30" fill="#d6dde3"/>'
 return `<svg xmlns="http://www.w3.org/2000/svg" width="720" height="1080" viewBox="0 0 720 1080" preserveAspectRatio="xMidYMin meet" fill="none">
 <defs>
  <linearGradient id="cloth" x1="184" y1="340" x2="565" y2="940" gradientUnits="userSpaceOnUse"><stop stop-color="#bbc7d3"/><stop offset=".25" stop-color="#8297af"/><stop offset=".48" stop-color="#344c68"/><stop offset=".7" stop-color="#637b96"/><stop offset="1" stop-color="#142439"/></linearGradient>
  <linearGradient id="edge" x1="205" y1="370" x2="511" y2="970" gradientUnits="userSpaceOnUse"><stop stop-color="#ecede6"/><stop offset=".44" stop-color="#b9cadc"/><stop offset="1" stop-color="#496786" stop-opacity=".2"/></linearGradient>
  <linearGradient id="hood" x1="275" y1="137" x2="416" y2="299" gradientUnits="userSpaceOnUse"><stop stop-color="#8297ae"/><stop offset=".45" stop-color="#405b78"/><stop offset="1" stop-color="#13283f"/></linearGradient>
  <linearGradient id="shade"><stop stop-color="#081729"/><stop offset="1" stop-color="#283f59"/></linearGradient>
 </defs>
 <g${child?' transform="translate(38 72) scale(.88)"':''}>
  <path d="${cloak}" fill="url(#cloth)" stroke="#d8e2e9" stroke-opacity=".4" stroke-width="1.4"/>
  <path d="M258 365C193 517 177 735 136 1025l68 53c17-243 28-470 73-651m160-64c42 177 48 441 91 704l76-50c-44-290-59-500-144-648" fill="#162c46" fill-opacity=".5"/>
  <path d="M279 380c-1 207-45 442-67 688m82-618c-3 231-23 407-15 623m47-594c-6 214 5 396-9 590m72-590c-16 202 23 407 42 588m4-665c13 185 39 387 69 657m-258-69c10-182 12-366 34-510" stroke="url(#edge)" stroke-width="2.2"/>
  <path d="M234 469c-23 117-46 270-70 414m91-291c-18 124-29 233-40 330m193-369c9 179 45 301 45 439m9-463c21 130 41 278 58 374M329 660c3 77-11 163-9 225" stroke="#e5e9e6" stroke-opacity=".2" stroke-width=".8"/>
  ${collar}
  <g transform="${head}">
   <path d="M284 293c-22-27-35-58-32-99 1-74 46-119 107-120 67-3 117 48 119 120 3 39-10 76-35 102l-18 30-67 34-64-35Z" fill="url(#hood)"/>
   <path d="M285 212c-1-69 32-104 75-104 49 0 79 44 77 107l-18 73-57 42-55-43Z" fill="url(#shade)"/>
   <path d="M259 220c5-81 45-133 103-135 54-1 99 50 109 132m-198-17c16-48 44-80 88-82 37 0 65 34 80 81" stroke="#dae2e4" stroke-opacity=".65" stroke-width="2"/>
   <path d="${turn?'M383 140c-9 37-11 56-5 75l20 25-19 8-12 35-27 22':'M316 144c-14 39-19 81-9 118l26 41'}" stroke="#8da4bb" stroke-opacity=".6" stroke-width="1.3"/>
   <path d="M270 255c18 55 47 87 89 102m88-104c-15 56-49 89-88 104" stroke="#aebfd0" stroke-opacity=".5" stroke-width="1.4"/>
   <path d="m280 272 20 25m-13-4 30 31m116-48-18 29m10-10-31 34" stroke="#ebede8" stroke-opacity=".28"/>
  </g>
  <path d="${arm}" fill="url(#cloth)" stroke="#d9e0e4" stroke-opacity=".65" stroke-width="1.5"/>
  <path d="${hand}" fill="#61758e" stroke="#cad5df" stroke-opacity=".5"/>
  <path d="M213 377c-23 54-37 132-41 207m54-184c-20 61-33 118-33 171M500 389c20 43 34 101 44 148" stroke="#c5d4df" stroke-opacity=".6" stroke-width="1.2"/>
  <path d="m135 943 28-17m-30 29 26-15m404-3 24 15m-23-4 24 16" stroke="#a8b9ce" stroke-opacity=".35"/>
  <path d="M274 325c-58 44-82 121-99 202M449 324c67 61 85 116 104 210" stroke="#c6daf1" stroke-opacity=".7" stroke-width="2"/>
 </g></svg>`
}

export function symbolicPortrait(person={},scope='DREAMER',world={},context={}){
 person=person || {};world=world || {};context=context || {}
 const visible=person.publicAppearance || person.known?.appearance || {}
 const info=scope==='OMNISCIENT'?{...visible,...(person.appearance || {})}:visible
 const preferredCulture=cultureOf(world.culturalStyle || person.worldStyle || context.culture || info.culturalStyle || 'MIXED')
 const culture=['WESTERN','EASTERN','MODERN','SCIFI','MIXED'].includes(preferredCulture)?preferredCulture:'MIXED'
 const ageGroup=context.ageGroup || info.apparentAgeGroup || info.ageGroup || (Number.isFinite(info.apparentAge || info.age)?(Number(info.apparentAge || info.age)<14?'CHILD':'ADULT'):'ADULT')
 const pose=poses[hash(person.id || person.godId || 'journey')%poses.length]
 const child=ageGroup==='CHILD',key=`silhouette-${culture.toLowerCase()}-${child?'child':'adult'}-${pose.toLowerCase()}`
 if(!cache.has(key))cache.set(key,figure(culture,pose,child))
 const asset=commonPortraits[child?2:pose==='TURN'?1:0]
 return {key,src:asset.src,markup:cache.get(key),portraitAssetId:asset.key,label:'人物剪影',symbolic:true,cinematic:true,matchKind:'symbolic',culture,gender:context.gender || info.gender || 'unknown',ageGroup,pose,
  framing:{stage:{'--portrait-inset':'4% -10% -38% -4%','--portrait-wide-inset':'4% -4% -40% -10%','--portrait-mobile-inset':'-2% -4% -130% 30%'},thumbnail:{objectPosition:'50% 14%'}}}
}
