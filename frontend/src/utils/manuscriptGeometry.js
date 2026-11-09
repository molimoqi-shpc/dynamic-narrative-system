// Presentation coordinates only: the paper follows the real writing strip,
// rather than a portrait-stage percentage unrelated to the controls.
export function manuscriptGeometry({width,height,top=72,formLeft,formTop,formWidth,formHeight}) {
 if(![width,height,formLeft,formTop,formWidth,formHeight].every(Number.isFinite) || width<=0 || height<=0 || formWidth<=0 || formHeight<=0)return null
 const scale=Math.min(1.15,Math.max(.7,formHeight/56))
 const start={x:width-40,y:Math.max(height*.28,formTop-140*scale)}
 const tip={x:formLeft+formWidth+56*scale,y:Math.min(height-12,formTop+formHeight+30*scale)}
 const end={x:tip.x-(height-tip.y)*2.5,y:height+1}
 const n=value=>Math.round(value*100)/100
 const point=p=>`${n(p.x)} ${n(p.y)}`
 const along=(t,offset=0)=>({x:start.x+(tip.x-start.x)*t,y:start.y+(tip.y-start.y)*t+offset})
 // Preserve the established upper manuscript/ink-silhouette overlap; only
 // its lower seam follows the writing controls.
 const upperWidth=width+42
 const page=`M-16 ${-top-1}H${n(-12+upperWidth*.84)}L${n(-12+upperWidth*.95)} ${n((height+top)*.49-top)}L${point(start)} ${point(tip)} ${point(end)}H-16Z`
 return {width,height,start,tip,end,page,
  edge:`M${point(start)}L${point(tip)} ${point(end)}`,
  accent:`M${point(along(.04,9))}L${point(along(.21,9))}M${point(along(.25,9))}L${point(along(.29,9))}M${point(along(.7,9))}L${point(along(.94,9))}`,
  echo:`M${point(along(.1,4))}L${point(along(.34,4))}M${point(along(.66,4))}L${point(along(.96,4))}`}
}
