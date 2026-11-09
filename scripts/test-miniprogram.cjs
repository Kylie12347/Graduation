/* 用 wx 替身验证柜号、上报内容、审核等待与付款弹窗。 */
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const storefront=require('../miniprogram/utils/storefront');
function page(name,request,globalData={},extraWx={}){
  let p,route='', modal=null; const notices=[];
  const api={request,money:n=>(n/100).toFixed(2),message:e=>notices.push(e.message),states:{OPEN:'购物中',REVIEW:'等待运营审核',UNPAID:'待支付',PAID:'已支付'}};
  const wx={navigateTo:o=>{route=o.url},switchTab:o=>{route=o.url},showModal:o=>{modal=o},showToast:()=>{},
    getStorageSync:()=>null,setStorageSync:()=>{},removeStorageSync:()=>{},...extraWx};
  vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../miniprogram/pages',name,name+'.js'),'utf8'),{Page:x=>{p=x},require:x=>x.endsWith('/api')?api:storefront,getApp:()=>({globalData}),wx,setInterval:()=>1,clearInterval:()=>{},console});
  p.setData=values=>Object.assign(p.data,values);
  return {p,route:()=>route,modal:()=>modal,notices};
}
(async()=>{
  assert.equal(storefront.parseCabinetId('CAB001'),'CAB001');
  assert.equal(storefront.parseCabinetId('cabinetId=CAB002'),'CAB002');
  assert.equal(storefront.parseCabinetId('pages/home/home?scene=cabinetId%3DCAB003'),'CAB003');
  assert.equal(storefront.parseCabinetId('%E0%A4%A'),'');
  const calls=[];
  const catalog=id=>({cabinet:{id,name:id,available:true},products:[]});
  const homeRequest=async(url,method,data)=>{
    calls.push([url,method,data]);
    if(url==='/cabinets')return [{id:'CAB001'},{id:'CAB002'}];
    if(url==='/sessions')return method==='POST'?{id:'S2',state:'OPEN',cabinet_id:data.cabinetId}:[];
    return catalog(url.split('/')[2]);
  };
  const launch=page('home',homeRequest,{pendingCabinetId:'CAB002'});
  launch.p.visible=true;
  await launch.p.refresh();
  assert.equal(calls.find(c=>c[1]==='POST')[2].cabinetId,'CAB002');
  assert.equal(launch.route(),'/pages/simulation/simulation');
  assert.equal(launch.p.data.cabinet.id,'CAB002');

  let observed;
  const simulation=page('simulation',async(url,method,body)=>{
    if(url==='/sessions')return [{id:'S2',state:'OPEN',cabinet_id:'CAB002'}];
    if(url.endsWith('/simulation'))return {grams:1000,tags:['TAG-A','TAG-B'],tagProducts:{'TAG-A':'COLA','TAG-B':'JUICE'},catalog:[{id:'COLA',name:'可乐'},{id:'JUICE',name:'橙汁'}]};
    if(method==='POST'){ observed=JSON.parse(JSON.stringify(body));return {id:'S2',cabinet_id:'CAB002',state:'REVIEW'};}
    return {cabinetName:'二号柜'};
  });
  await simulation.p.load();
  simulation.p.input({currentTarget:{dataset:{field:'after'}},detail:{value:'670'}});
  simulation.p.input({currentTarget:{dataset:{field:'errorGrams'}},detail:{value:'-3'}});
  simulation.p.toggleTag({currentTarget:{dataset:{tag:'TAG-A',field:'after'}}});
  await simulation.p.submit();
  assert.deepEqual(observed,{beforeGrams:1000,afterGrams:667,beforeTags:['TAG-A','TAG-B'],afterTags:['TAG-B'],photos:[]});
  assert.equal(simulation.route(),'/pages/shopping/shopping?id=S2');

  let phase='REVIEW',paid=0;
  const checkout=page('shopping',async(url,method)=>{
    if(method==='POST'){paid++;return {state:'PAID'};}
    return {id:'S2',cabinet_id:'CAB002',cabinetName:'二号柜',state:phase,order_id:'O2',items:[],order:{items:[],total_cents:350,checkoutRoute:'ADMIN_REVIEW'}};
  });
  checkout.p.onLoad({id:'S2'});checkout.p.visible=true;
  await checkout.p.refresh();
  assert.equal(checkout.modal(),null);
  assert.equal(checkout.p.resolve,undefined);
  phase='UNPAID';await checkout.p.refresh();
  assert.equal(checkout.modal().title,'待支付 · CAB002');
  assert.equal(paid,0);
  await checkout.p.pay({currentTarget:{dataset:{success:'true'}}});
  assert.equal(paid,1);
  assert.equal(checkout.route(),'/pages/home/home');
  console.log('PASS：扫码指定柜体、前端观测上报、运营审核等待、付款弹窗和付款后退出。');
})().catch(e=>{console.error(e);process.exitCode=1;});
