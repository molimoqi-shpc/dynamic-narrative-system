export function sceneAtmosphere(scene) {
 const name=String(scene?.locationName || '')
 const source=name || String(scene?.sceneSummary || '')
 if(/渡|河|海|湖|水|岸|桥|汀|滩|溪|湾|潭/.test(source))return {key:'water',position:'100% 30%',label:'水岸'}
 if(/林|森|竹|荒野|山谷/.test(source))return {key:'forest',position:'50% 30%',label:'林间'}
 return {key:'temple',position:'0% 30%',label:'云境'}
}
