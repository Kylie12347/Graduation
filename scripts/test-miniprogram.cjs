/* 无微信运行环境时的页面逻辑测试。真实wx接口用替身，验证请求、路由及消费者呈现。 */
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const root=path.resolve(__dirname,'..');
const storefront=require('../miniprogram/utils/storefront');
let checks=0;
function pass(name){checks++;console.log('PASS '+name);}
function page(name,request,globalData={},extraWx={}){
 let definition,route='';
 const api={request,money:n=>(n/100).toFixed(2),message:e=>{throw e},states:{OPEN:'购物中',REVIEW:'待确认清单'}};
 const wx={navigateTo:o=>{route=o.url},switchTab:o=>{route=o.url},navigateBack:()=>{route='BACK'},showToast:()=>{},...extraWx};
 vm.runInNewContext(fs.readFileSync(path.join(root,'miniprogram/pages/'+name+'/'+name+'.js'),'utf8'),{Page:p=>{definition=p},require:p=>p.endsWith('/api')?api:storefront,getApp:()=>({globalData}),wx,setInterval:()=>1,clearInterval:()=>{},console});
 definition.data=JSON.parse(JSON.stringify(definition.data));definition.setData=function(p){Object.assign(this.data,p)};
 return {p:definition,route:()=>route};
}
(async()=>{
 assert.equal(storefront.parseCabinetId('CAB001'),'CAB001');
 assert.equal(storefront.parseCabinetId('https://example.test/?cabinetId=CAB002'),'CAB002');
 assert.equal(storefront.parseCabinetId('pages/home/home?scene=cabinetId%3DCAB003'),'CAB003');
 assert.equal(storefront.parseCabinetId('%E0%A4%A'),'');assert.equal(storefront.parseCabinetId('CAB001/other'),'');
 pass('二维码原始编号、URL、小程序scene与非法值');
 const catalog={cabinet:{id:'CAB001',name:'一号柜',location:'图书馆',available:true},products:[{id:'WATER',name:'矿泉水',price_cents:200,stock:5}]};
 let calls=[];
 const gd={boundCabinetId:'',pendingCabinetId:'',previewCabinetId:''};
 const h=page('home',async(url,method,body)=>{calls.push([url,method,body]);if(url==='/cabinets')return [{id:'CAB001'}];if(url==='/sessions'&&method==='POST')return {id:'S1'};if(url==='/sessions')return [];return catalog},gd,{scanCode:async o=>o.success({result:'CAB001'})});
 h.p.visible=true;await h.p.refresh();assert.equal(h.p.data.bound,false);assert.equal(h.p.data.products[0].price,'2.00');assert.ok(h.p.data.products[0].image.endsWith('water.png'));
 await assert.rejects(h.p.openBound(),/请先扫描/);assert.ok(!calls.some(c=>c[1]==='POST'));pass('首页预览商品、图片和价格，未扫码不能开柜');
 const scan=page('home',async(url,method,body)=>{calls.push([url,method,body]);return method==='POST'?{id:'S2'}:catalog},gd,{scanCode:o=>o.success({result:'CAB001'})});
 await new Promise((resolve,reject)=>{scan.p.openCabinet=async id=>{try{assert.equal(id,'CAB001');assert.equal(gd.boundCabinetId,'CAB001');resolve()}catch(e){reject(e)}};scan.p.scan()});pass('扫码绑定唯一柜体并触发该柜开门');
 const incoming={pendingCabinetId:'CAB001',boundCabinetId:''};const launch=page('home',async u=>u==='/cabinets'?[{id:'CAB001'}]:u==='/sessions'?[]:catalog,incoming);launch.p.visible=true;await launch.p.refresh();assert.equal(launch.p.data.bound,true);assert.equal(incoming.pendingCabinetId,'');pass('小程序码启动参数绑定已验证柜体');
 const session={id:'S1',cabinet_id:'CAB001',state:'REVIEW',pendingCount:1,items:[],basketError:'',events:[{id:'E1',status:'PENDING',result:{direction:'TAKE',candidates:[{productId:'WATER',name:'矿泉水'}],steps:['private trace'],reason:'private reason'}}]};
 const shopping=page('shopping',async()=>session);shopping.p.id='S1';shopping.p.visible=true;await shopping.p.refresh();assert.equal(shopping.p.data.pending.length,1);assert.ok(!JSON.stringify(shopping.p.data).includes('private trace'));assert.ok(!JSON.stringify(shopping.p.data).includes('private reason'));
 shopping.p.resolve({currentTarget:{dataset:{id:'E1'}}});assert.equal(shopping.route(),'/pages/resolve/resolve?sessionId=S1&eventId=E1');pass('消费者视图不含融合日志，待确认项目进入独立页面');
 let saved;
 const selection=page('resolve',async(url,method,body)=>{if(method==='POST'){saved=body;return {}}return url.includes('/catalog')?catalog:session});selection.p.onLoad({sessionId:'S1',eventId:'E1'});await selection.p.load();assert.equal(selection.p.data.selectedId,'');await selection.p.save();assert.equal(saved,undefined);
 selection.p.selectProduct({currentTarget:{dataset:{id:'WATER'}}});selection.p.increase();selection.p.selectDirection({currentTarget:{dataset:{direction:'RETURN'}}});await selection.p.save();assert.equal(saved.productId,'WATER');assert.equal(saved.quantity,2);assert.equal(saved.direction,'RETURN');assert.ok(saved.note.includes('消费者核对'));assert.equal(selection.route(),'BACK');pass('必须点击商品才能确认，按钮修改方向数量，保存后返回');
 session.state='PAID';await selection.p.load();assert.equal(selection.p.data.editable,false);saved=undefined;await selection.p.save();assert.equal(saved,undefined);pass('已结算清单不可再次提交人工答案');
 const nearby=page('nearby',async()=>[] ,gd);nearby.p.browse({currentTarget:{dataset:{id:'CAB002'}}});assert.equal(gd.previewCabinetId,'CAB002');assert.equal(gd.boundCabinetId,'CAB001');pass('附近列表只浏览，不改变扫码绑定柜体');
 for(const name of ['shopping','resolve'])assert.ok(!fs.readFileSync(path.join(root,'miniprogram/pages/'+name+'/'+name+'.wxml'),'utf8').includes('<picker'));
 assert.ok(!fs.readFileSync(path.join(root,'miniprogram/pages/shopping/shopping.wxml'),'utf8').includes('result.steps'));
 for(const name of ['cola','water','tea','juice','biscuit','generic'])assert.ok(fs.existsSync(path.join(root,'miniprogram/assets/products/'+name+'.png')));
 pass('无滚轮选择、无融合日志模板，六张商品图片齐全');const paidSession={id:'S9',cabinet_id:'CAB001',state:'PAID',pendingCount:0,items:[],events:[],order_id:'O9',order:{total_cents:200,items:[]}};
 const checkoutData={boundCabinetId:'CAB001',pendingCabinetId:'CAB001',previewCabinetId:'CAB001'};
 const paidPage=page('shopping',async()=>paidSession,checkoutData);paidPage.p.onLoad({id:'S9'});paidPage.p.visible=true;await paidPage.p.refresh();
 assert.equal(paidPage.route(),'/pages/home/home');assert.equal(checkoutData.boundCabinetId,'');assert.equal(checkoutData.pendingCabinetId,'');pass('付款已完成时自动返回首页并清理扫码绑定');
 const history=page('shopping',async()=>paidSession,{});history.p.onLoad({id:'S9',readOnly:'1'});history.p.visible=true;await history.p.refresh();assert.equal(history.route(),'');assert.equal(history.p.data.session.state,'PAID');pass('从历史订单查看已支付清单时保留详情页面');
 let paymentSucceeded=false;
 const unpaid={...paidSession,state:'UNPAID'};
 const payment=page('shopping',async(u,m)=>u.endsWith('/pay')?{state:paymentSucceeded?'PAID':'UNPAID'}:unpaid,{});
 payment.p.onLoad({id:'S9'});payment.p.visible=true;payment.p.data.session={order_id:'O9'};
 await payment.p.pay({currentTarget:{dataset:{success:'true'}}});assert.equal(payment.route(),'');
 paymentSucceeded=true;await payment.p.pay({currentTarget:{dataset:{success:'true'}}});assert.equal(payment.route(),'/pages/home/home');pass('只按后端PAID跳转，支付未成功留在支付页');
 const homeClosed=page('home',async u=>u==='/cabinets'?[{id:'CAB001'}]:u==='/sessions'?[{id:'S1',cabinet_id:'CAB001',state:'REVIEW'}]:catalog,{pendingCabinetId:''});homeClosed.p.visible=true;await homeClosed.p.refresh();assert.equal(homeClosed.route(),'/pages/shopping/shopping?id=S1');pass('首页轮询发现管理端关柜后自动进入结算页');
 const onceData={boundCabinetId:'CAB001',pendingCabinetId:'CAB001',previewCabinetId:'CAB001'};
 const once=page('home',async()=>({id:'S3',state:'OPEN'}),onceData);await once.p.openCabinet('CAB001');assert.equal(onceData.boundCabinetId,'');assert.equal(once.p.data.bound,false);assert.equal(once.p.data.active.id,'S3');pass('一次扫码绑定在开柜成功后消耗，不保留重复开柜按钮');
 console.log('RESULT passed='+checks+' failed=0');
})().catch(e=>{console.error(e);process.exitCode=1});
