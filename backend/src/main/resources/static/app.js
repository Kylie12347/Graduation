/*
 * 管理台使用浏览器原生JavaScript，不依赖npm。
 * const/let声明变量；async/await组织异步请求；模板字符串生成页面结构。
 * 所有服务器文本在插入innerHTML前都经过esc转义，避免商品名称被当作HTML执行。
 */
const $ = selector => document.querySelector(selector);
const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const money = cents => '¥' + (Number(cents || 0) / 100).toFixed(2);
const time = value => value ? new Date(value).toLocaleString('zh-CN', {timeZone:'Asia/Shanghai',hour12:false}) : '—';
const names = {OPEN:'购物中',REVIEW:'待运营审核',UNPAID:'待支付',PAID:'已支付',CANCELLED:'已取消',PENDING:'待人工确认',CONFIRMED:'自动确认',RESOLVED:'人工已确认',SUCCESS:'模拟支付成功',FAILED:'模拟支付失败',TAKE:'取出',RETURN:'放回',INITIAL:'初始库存',RESTOCK:'补货',SALE:'支付销售'};
const badge = status => `<span class="badge ${status==='PENDING'?'warn':status==='CANCELLED'?'red':''}">${esc(names[status] || status)}</span>`;
const state = {token:sessionStorage.getItem('adminToken'),tab:new URLSearchParams(location.search).has('cabinetId')?'audit':'dashboard',auditCabinet:new URLSearchParams(location.search).get('cabinetId')||'',dashboardCabinet:'',dashboardDate:'',orderFilters:{},products:[],cabinets:[],stocks:[],sessions:[],sessionId:'',session:null};
let toastTimer;
function toast(message) { $('#toast').textContent=message;$('#toast').classList.add('show');clearTimeout(toastTimer);toastTimer=setTimeout(()=>$('#toast').classList.remove('show'),4500); }
async function api(path, method='GET', body, token=state.token) {
  const response=await fetch('/api'+path,{method,headers:{'Content-Type':'application/json',Authorization:'Bearer '+token},body:body===undefined?undefined:JSON.stringify(body)});
  const data=await response.json();
  if(!response.ok){if(response.status===401 && token===state.token){sessionStorage.removeItem('adminToken');state.token=null;showLogin();}throw new Error(data.message || '请求失败');}
  return data;
}
function showLogin(){ $('#login').classList.remove('hidden');$('#workspace').classList.add('hidden'); }
function table(headers, rows) { return `<div class="table-wrap"><table><thead><tr>${headers.map(h=>`<th>${esc(h)}</th>`).join('')}</tr></thead><tbody>${rows.length?rows.map(r=>`<tr>${r.map(c=>`<td>${c}</td>`).join('')}</tr>`).join(''):`<tr><td colspan="${headers.length}" class="empty">暂无记录，完成一次模拟购物后这里会出现数据</td></tr>`}</tbody></table></div>`; }
function selectProducts(selected='') {return state.products.map(p=>`<option value="${esc(p.id)}" ${p.id===selected?'selected':''}>${esc(p.name)}（${p.weightGrams}克）</option>`).join('');}
function selectCabinets(selected){return state.cabinets.map(c=>`<option value="${esc(c.id)}" ${c.id===selected?'selected':''}>${esc(c.name)} / ${esc(c.id)}</option>`).join('');}
async function loadBase(){[state.products,state.cabinets,state.stocks,state.sessions]=await Promise.all([api('/products'),api('/cabinets'),api('/admin/stocks'),api('/admin/sessions')]);}
async function render(){
  if(!state.token) return showLogin();
  $('#login').classList.add('hidden');$('#workspace').classList.remove('hidden');
  const titles={dashboard:'经营概览',audit:'柜体与开关柜审核',products:'商品与库存',orders:'订单与交易',logs:'库存流水'};
  $('#page-title').textContent=titles[state.tab];
  document.querySelectorAll('#nav button').forEach(b=>b.classList.toggle('active',b.dataset.tab===state.tab));
  await loadBase();
  if(state.tab==='dashboard') await dashboard();
  else if(state.tab==='audit') await auditPage();
  else if(state.tab==='products') productsPage();
  else if(state.tab==='orders') await ordersPage();
  else await logsPage();
}
async function dashboard(){
  const query=new URLSearchParams({cabinetId:state.dashboardCabinet});
  if(state.dashboardDate)query.set('date',state.dashboardDate);
  const d=await api('/admin/dashboard?'+query);
  if(state.tab!=='dashboard')return;
  const peak=Math.max(...d.hourlyRevenue,1);
  $('#content').innerHTML=`<section class="hero"><div><span class="eyebrow">EVERY TRANSACTION TELLS A STORY</span><p><strong>看得见的库存，算得清的每一单。</strong></p><p>按支付成功时间统计 · 上海时区 · 不计入失败和取消订单</p></div><div><label>统计范围<select id="dashboard-cabinet"><option value="">全部售货柜</option>${selectCabinets(state.dashboardCabinet)}</select></label><label>查看营业日期<input id="business-date" type="date" value="${esc(d.date)}"></label></div></section>
    <section class="stats"><div class="stat"><span>当日营业额</span><strong>${money(d.revenueCents)}</strong><small>全部为模拟支付金额</small></div><div class="stat"><span>成功订单</span><strong>${d.paidOrders}<small> 单</small></strong><small>重复支付只记账一次</small></div><div class="stat"><span>售出商品</span><strong>${d.soldQuantity}<small> 件</small></strong><small>以模拟支付成功为准</small></div><div class="stat"><span>需要补货</span><strong>${d.lowStock.length}<small> 项</small></strong><small>另有 ${d.pendingEvents} 笔会话待运营审核</small></div></section>
    <div class="grid-two"><section class="panel"><div class="panel-head"><h2>24小时营业走势</h2><span class="subtle">元 / 小时</span></div><div class="chart">${d.hourlyRevenue.map((v,i)=>`<div class="chart-col" title="${i}时 ${money(v)}"><span class="bar" style="height:${Math.max(2,v/peak*135)}px"></span><small>${i%3===0?i+'时':''}</small></div>`).join('')}</div></section><section class="panel"><h2>补货提醒</h2>${d.lowStock.length?d.lowStock.map(r=>`<div class="alert-row"><b>${esc(r.name)}</b> · ${esc(r.cabinet_id)}<br>柜内剩余 ${r.on_hand} 件 / 预警线 ${r.threshold_qty} 件</div>`).join(''):'<p class="empty">库存充足，暂时无需补货</p>'}</section></div>
    <div class="grid-two"><section class="panel"><h2>当日商品销售</h2>${table(['商品','售出件数','销售金额'],d.productSales.map(r=>[esc(r.name),r.quantity,money(r.amountCents)]))}</section><section class="panel"><h2>各柜经营对比</h2><p class="subtle">比较同一营业日的所有柜体，包含零销量柜。</p>${table(['柜体','营业额','订单数','售出件数'],d.comparison.map(c=>[esc(c.name)+' / '+esc(c.id),money(c.revenueCents),c.orders,c.units]))}</section></div>
    <section class="panel"><h2>实时库存 · 累计销售</h2>${inventoryTable(d.stocks)}</section>`;
  $('#business-date').onchange=e=>{state.dashboardDate=e.target.value;dashboard().catch(fail);};
  $('#dashboard-cabinet').onchange=e=>{state.dashboardCabinet=e.target.value;dashboard().catch(fail);};
}
function inventoryTable(rows){return table(['柜体 / 商品','实际柜内','待结算取出','累计已售','阈值','库存核对'],rows.map(r=>[`${esc(r.cabinet_id)} / ${esc(r.name)}`,`${r.on_hand} ${r.lowStock?'<span class="badge warn">需补货</span>':''}`,r.held,r.sold,r.threshold_qty,r.discrepancy?`<span class="error">差异 ${r.discrepancy} 件，需核对</span>`:'<span class="subtle">一致</span>']));}
async function auditPage(){
  const allowed=state.sessions.filter(s=>!state.auditCabinet || s.cabinet_id===state.auditCabinet);
  if(!allowed.some(s=>s.id===state.sessionId)) state.sessionId=allowed[0]?.id || '';
  $('#content').innerHTML=`<section class="panel"><div class="panel-head"><h2>柜体二维码</h2><span class="subtle">用小程序首页的扫码按钮扫描。后台和小程序按同一个柜号关联。</span></div><div class="cabinet-grid">${state.cabinets.map(c=>`<article class="cabinet-card"><h3>${esc(c.name)} · ${esc(c.id)}</h3><p>${esc(c.location)} · ${c.door_open?'柜门打开':c.active_session?'等待结算':'可用'}</p><img class="cabinet-qr" alt="${esc(c.id)} 的开柜二维码" src="/api/cabinets/${encodeURIComponent(c.id)}/qr"><p><a href="/?cabinetId=${encodeURIComponent(c.id)}">查看此柜的会话</a></p></article>`).join('')}</div></section>
    <section class="panel"><div class="panel-head"><h2>开关柜记录</h2><button id="refresh-audit">刷新会话</button></div>
    <div class="form-grid"><label>柜体<select id="audit-cabinet"><option value="">全部柜体</option>${selectCabinets(state.auditCabinet)}</select></label><label>购物会话<select id="audit-session"><option value="">请选择会话</option>${allowed.map(s=>`<option value="${esc(s.id)}" ${state.sessionId===s.id?'selected':''}>${esc(s.cabinet_id)} / ${time(s.created_at)} / ${esc(names[s.state]||s.state)}</option>`).join('')}</select></label></div></section><div id="audit-detail"></div>`;
  $('#audit-cabinet').onchange=e=>{state.auditCabinet=e.target.value;state.sessionId='';auditPage().catch(fail);};
  $('#audit-session').onchange=e=>{state.sessionId=e.target.value;loadAudit().catch(fail);};
  $('#refresh-audit').onclick=()=>render().catch(fail);
  await loadAudit();
}
const visionStatus={OK:'识别完成',NEED_PHOTOS:'未提供足够照片',NOT_CONFIGURED:'未配置视觉模型',MODEL_ERROR:'模型调用或输出异常',UNCERTAIN:'照片不足以判断',NO_CATALOG:'本柜无在售商品'};
function displayTags(tags, bindings, goods){
  const label=Object.fromEntries(goods.map(p=>[p.id,p.name]));
  return `<details><summary>查看 ${tags.length} 个 EPC</summary><div class="audit-tags">${tags.map(t=>`<div><b>${esc(label[bindings[t]]||bindings[t])}</b> · ${esc(t)}</div>`).join('')}</div></details>`;
}
async function loadAudit(){
  const target=$('#audit-detail'), id=state.sessionId;
  if(!id){target.innerHTML='<p class="empty">这台柜暂时没有扫码会话。</p>';return;}
  target.innerHTML='<p class="subtle">加载会话记录…</p>';
  const a=await api('/admin/sessions/'+encodeURIComponent(id)+'/audit');
  if(id!==state.sessionId || target!==$('#audit-detail')) return;
  if(!a.before){
    target.innerHTML=`<section class="panel"><h2>历史会话 · ${esc(a.cabinet_id)}</h2><p>旧版会话未记录开关柜快照。</p>${!['PAID','CANCELLED'].includes(a.state)?'<button id="legacy-recover">归还旧版仿真商品并结束会话</button>':''}</section>`;
    const recover=$('#legacy-recover');
    if(recover) recover.onclick=()=>act(async()=>{if(!confirm('归还该旧会话的商品并取消订单？'))return;await api('/admin/sessions/'+encodeURIComponent(id)+'/return-and-cancel','POST');await render();});
    return;
  }
  const b=a.before,z=a.after, goods=b.catalog;
  const namesById=Object.fromEntries(goods.map(p=>[p.id,p.name]));
  const qtyText=counts=>Object.entries(counts).map(([sku,qty])=>esc(namesById[sku]||sku)+' × '+qty).join('、')||'无';
  const validPhoto=url=>/^data:image\/(jpeg|png);base64,[A-Za-z0-9+/]+=*$/.test(url);
  const photos=(z?.photos||[]).filter(p=>validPhoto(p.dataUrl));
  const cards=`<div class="audit-timeline">
    <article class="audit-message"><h3>开柜前 <small>${time(a.created_at)}</small></h3>
    <p><b>重量：</b>${z?.beforeGrams??b.grams} 克 ${z?'':'（初始快照，等候小程序上报实测模拟值）'}</p>
    <div><b>RFID：</b>${displayTags(z?.beforeTags||b.tags,b.tagProducts,goods)}</div>
    <p><b>视觉：</b>${photos.length?'见下方时间序列的第一帧':'等待开关柜照片'}</p></article>
    <article class="audit-message after"><h3>关柜后 <small>${time(a.closed_at)}</small></h3>
    ${z?`<p><b>重量：</b>${z.afterGrams} 克；净减少 ${z.beforeGrams-z.afterGrams} 克</p>
    <p>重量候选：${z.weightCandidates.map(p=>esc(p.name)+' × '+p.quantity).join('、')||'无'}</p>
    <div><b>RFID 净取出：</b>${qtyText(z.rfidItems)}；新增读到 ${z.addedTags.length} 个${displayTags(z.afterTags,b.tagProducts,goods)}</div>
    <p><b>视觉：</b>${esc(visionStatus[z.vision.status]||z.vision.status)}${z.vision.items.length?' · '+z.vision.items.map(i=>esc(i.name)+' × '+i.quantity).join('、'):z.vision.status==='OK'?' · 无净取出':''}</p>`:'<p class="subtle">尚未收到关柜读数。</p>'}</article></div>`;
  const review=z&&a.state==='REVIEW'?`<section class="panel"><h2>运营审核</h2><p>对照以上观测确定实际净取出量。所有数量填 0 表示确认无商品需结算。</p>
    <form id="audit-form"><div class="form-grid">${goods.map(g=>`<label>${esc(g.name)} · 开柜库存 ${g.on_hand}<input data-sku="${esc(g.id)}" type="number" min="0" max="${Math.min(g.on_hand,1000)}" value="0" required></label>`).join('')}</div>
    <label>审核依据<textarea name="note" maxlength="500" required placeholder="例如：照片清楚显示取出了橙汁，RFID 错记为可乐。"></textarea></label><button class="primary">提交审核结果</button></form></section>`:'';
  target.innerHTML=`<section class="panel"><div class="panel-head"><h2>${esc(a.cabinetName)} · ${esc(a.cabinet_id)}</h2>${badge(a.state)}</div>${cards}
    ${photos.length?`<h3>视觉取放过程</h3><div class="audit-photos">${photos.map((p,i)=>`<figure><img src="${esc(p.dataUrl)}" alt="取放过程第 ${i+1} 帧"><figcaption>第 ${i+1} 帧 · ${time(p.timestamp)}</figcaption></figure>`).join('')}</div>`:''}
    ${a.decisionNote?`<p><b>处理依据：</b>${esc(a.decisionNote)}</p>`:''}${a.order?.items?.length?table(['确认商品','数量','小计'],a.order.items.map(i=>[esc(i.name),i.quantity,money(i.subtotalCents)])):''}</section>${review}
    ${a.state==='OPEN'?'<section class="panel"><button id="abandon-session">结束未提交的空柜会话</button></section>':''}
    ${a.state==='UNPAID'?'<section class="panel"><h2>已归还商品</h2><form id="cancel-unpaid"><label>说明<input name="note" maxlength="500" required placeholder="核实商品已全部归还后填写"></label><button class="danger">确认归还并取消未付款订单</button></form></section>':''}`;
  const abandon=$('#abandon-session');
  if(abandon) abandon.onclick=()=>act(async()=>{if(!confirm('确认小程序尚未提交且未取出商品？'))return;await api('/sessions/'+encodeURIComponent(id)+'/abandon','POST');await render();});
  const cancel=$('#cancel-unpaid');
  if(cancel)cancel.onsubmit=e=>{e.preventDefault();act(async()=>{
    if(!confirm('请核实商品已全部归还，是否恢复库存并关闭未付款订单？')) return;
    await api('/admin/sessions/'+encodeURIComponent(id)+'/cancel-unpaid','POST',{note:cancel.elements.note.value.trim()});
    await render();toast('库存已恢复，订单已取消');
  });};
  const form=$('#audit-form');
  if(form) form.onsubmit=e=>{e.preventDefault();act(async()=>{
    const items=[...form.querySelectorAll('[data-sku]')].filter(el=>Number(el.value)>0).map(el=>({ productId:el.dataset.sku,quantity:Number(el.value) }));
    if(!items.length&&!confirm('确认无商品结算并结束会话？')) return;
    await api('/admin/sessions/'+encodeURIComponent(id)+'/review','POST',{items,note:form.elements.note.value.trim()});
    await render();toast(items.length?'审核完成，消费者现在可以付款':'已关闭，无需付款');
  });};
}
function productImage(p){
  const local={COLA:'cola',WATER:'water',TEA:'tea',JUICE:'juice',BISCUIT:'biscuit'};
  return /^https:\/\//i.test(p.imageUrl||'')?p.imageUrl:'/product-images/'+(local[p.id]||'generic')+'.png';
}
function productsPage(){
  $('#content').innerHTML=`<section class="panel"><div class="panel-head"><h2>商品资料</h2><span class="subtle">金额单位：分；重量单位：克</span></div>${table(['图片','编号','商品','价格','重量','识别规则','状态','操作'],state.products.map(p=>[`<img class="product-preview" src="${esc(productImage(p))}" alt="">`,esc(p.id),esc(p.name),money(p.priceCents),p.weightGrams,p.weightPriority?'重量优先':'RFID + 视觉',p.enabled?'在售':'已下架',`<button data-edit="${esc(p.id)}">编辑</button> <button data-tags="${esc(p.id)}">RFID 标签</button>`]))}<hr><form id="product-form"><div class="form-grid"><label>SKU编号<input name="id" required maxlength="40" pattern="[A-Za-z0-9_-]+" placeholder="如 MILK"></label><label>商品名称<input name="name" required maxlength="100"></label><label>售价（分）<input name="priceCents" type="number" value="500" min="1" required></label><label>标称重量（克）<input name="weightGrams" type="number" value="250" min="1" required></label><label>商品图片（HTTPS）<input name="imageUrl" type="url" maxlength="500" placeholder="留空使用系统示意图"></label><label>特殊商品规则<select name="weightPriority"><option value="false">RFID + 视觉优先</option><option value="true">唯一重量候选优先</option></select></label><label>补货预警线<input name="thresholdQty" type="number" value="3" min="0" required></label><label>销售状态<select name="enabled"><option value="true">在售</option><option value="false">下架</option></select></label></div><button class="primary">新增 / 保存商品</button><span class="subtle"> 同一编号为编辑；下架保留历史记录。购物期间禁止改价。</span></form></section>
    <section class="panel"><h2>库存与补货</h2>${inventoryTable(state.stocks)}<form id="restock-form"><div class="form-grid three"><label>柜体<select name="cabinetId">${selectCabinets()}</select></label><label>商品<select name="productId">${selectProducts()}</select></label><label>补货数量<input name="quantity" type="number" value="10" min="1" max="1000" required></label></div><button class="primary">确认补货入库</button></form></section>
    <section class="panel"><h2>新增虚拟柜体</h2><form id="cabinet-form"><div class="form-grid three"><label>柜体编号<input name="id" placeholder="CAB002" required pattern="[A-Za-z0-9_-]{1,40}"></label><label>柜体名称<input name="name" required maxlength="100"></label><label>位置<input name="location" required maxlength="200"></label></div><button>创建柜体</button></form></section>`;
  $('#product-form').onsubmit=e=>{e.preventDefault();act(async()=>{const data=Object.fromEntries(new FormData(e.target));['priceCents','weightGrams','thresholdQty'].forEach(k=>data[k]=Number(data[k]));data.enabled=data.enabled==='true';data.weightPriority=data.weightPriority==='true';await api('/admin/products','POST',data);await render();toast('商品已保存');});};
  document.querySelectorAll('[data-edit]').forEach(b=>b.onclick=()=>{const p=state.products.find(p=>p.id===b.dataset.edit);Object.entries(p).forEach(([k,v])=>{if($('#product-form').elements[k])$('#product-form').elements[k].value=v==null?'':String(v);});$('#product-form').scrollIntoView({behavior:'smooth'});});
  document.querySelectorAll('[data-tags]').forEach(b=>b.onclick=()=>act(async()=>{
    const p=state.products.find(p=>p.id===b.dataset.tags);
    $('#dialog-text').innerHTML=`<h3>${esc(p.name)} · ${esc(p.id)} 的单品 RFID</h3><label>选择柜体<select id="rfid-cabinet">${selectCabinets()}</select></label><div id="rfid-items" tabindex="0"></div>`;
    $('#detail-dialog').showModal();
    const select=$('#rfid-cabinet'),target=$('#rfid-items');
    const fetchTags=async()=>{
      if(!select.value) return;
      const cabinet=select.value;
      const rows=await api('/admin/rfid-items?cabinetId='+encodeURIComponent(cabinet));
      if(cabinet!==select.value)return;
      target.innerHTML=table(['EPC 标签','状态'],rows.filter(r=>r.product_id===p.id).map(r=>[esc(r.epc),esc(({IN_CABINET:'柜内',OUTSIDE:'待结算',SOLD:'已售'})[r.location]||r.location)]));
    };
    select.onchange=()=>fetchTags().catch(fail);await fetchTags();
  }));
  $('#restock-form').onsubmit=e=>{e.preventDefault();act(async()=>{const data=Object.fromEntries(new FormData(e.target));data.quantity=Number(data.quantity);await api('/admin/restock','POST',data);await render();toast('补货完成，库存与入库流水已更新');});};
  $('#cabinet-form').onsubmit=e=>{e.preventDefault();act(async()=>{await api('/admin/cabinets','POST',Object.fromEntries(new FormData(e.target)));await render();toast('虚拟柜体已创建，请补货后使用');});};
}
const checkoutNames={DIRECT:'直接付款',WEIGHT_PRIORITY:'直接付款（重量优先）',ADMIN_REVIEW:'后台审核后付款',PENDING:'待审核 / 未结算',LEGACY:'历史流程'};
async function ordersPage(){
  const query=new URLSearchParams(state.orderFilters);
  const orders=await api('/admin/orders?'+query);
  if(state.tab!=='orders')return;
  const opts=(values,chosen)=>Object.entries(values).map(([k,v])=>`<option value="${esc(k)}" ${chosen===k?'selected':''}>${esc(v)}</option>`).join('');
  $('#content').innerHTML=`<section class="panel"><h2>订单筛选</h2><form id="order-filters"><div class="form-grid three">
    <label>柜体<select name="cabinetId"><option value="">全部柜体</option>${selectCabinets(state.orderFilters.cabinetId)}</select></label>
    <label>订单状态<select name="state"><option value="">全部状态</option>${opts({REVIEW:names.REVIEW,UNPAID:names.UNPAID,PAID:names.PAID,CANCELLED:names.CANCELLED},state.orderFilters.state)}</select></label>
    <label>支付方式<select name="route"><option value="">全部方式</option>${opts(checkoutNames,state.orderFilters.route)}</select></label>
    </div><button class="primary">筛选</button><button type="button" id="clear-order-filters">清空</button></form></section>
    <section class="panel"><h2>匹配的最近 200 笔订单</h2>${table(['订单编号','柜体','状态','支付方式','金额','创建时间','支付时间','详情'],orders.map(o=>[esc(o.id.slice(0,8)),esc(o.cabinet_id),badge(o.state),esc(checkoutNames[o.checkout_route]||'—'),money(o.total_cents),time(o.created_at),time(o.paid_at),`<button data-order="${esc(o.id)}">查看交易</button>`]))}</section>`;
  $('#order-filters').onsubmit=e=>{e.preventDefault();state.orderFilters=Object.fromEntries(new FormData(e.target));ordersPage().catch(fail);};
  $('#clear-order-filters').onclick=()=>{state.orderFilters={};ordersPage().catch(fail);};
  document.querySelectorAll('[data-order]').forEach(b=>b.onclick=()=>act(async()=>{
    const o=await api('/orders/'+encodeURIComponent(b.dataset.order));
    $('#dialog-text').innerHTML=`<p><b>${esc(o.cabinet_id)}</b> · ${badge(o.state)} · ${esc(checkoutNames[o.checkoutRoute]||'待结算')}</p><p>订单号：${esc(o.id)}<br>创建：${time(o.created_at)}　支付：${time(o.paid_at)}<br><b>合计：${money(o.total_cents)}</b></p>
      <h3>商品清单</h3>${table(['商品','数量','单价','小计'],o.items.map(i=>[esc(i.name),i.quantity,money(i.unitPriceCents),money(i.subtotalCents)]))}
      <h3>模拟支付记录</h3>${o.payments.length?table(['时间','结果','金额'],o.payments.map(p=>[time(p.created_at),badge(p.state),money(p.amount_cents)])):'<p class="subtle">暂无支付记录。</p>'}`;
    $('#detail-dialog').showModal();
  }));
}
async function logsPage(){const rows=await api('/admin/inventory-logs');$('#content').innerHTML=`<section class="panel"><h2>最近200笔库存变化</h2><p class="subtle">取出/放回改变物理库存，支付销售只增加销量；二者分别记录。</p>${table(['时间','柜体','SKU','类型','件数','关联编号'],rows.map(r=>[time(r.created_at),esc(r.cabinet_id),esc(r.product_id),badge(r.kind),r.quantity,esc(r.reference_id?.slice(0,8)||'—')]))}</section>`;}
// 更新会话选项；运营人员填写审核表时不重绘表单。
let pollingAudit=false;
setInterval(async()=>{
  const select=$('#audit-session');
  if(pollingAudit||!state.token||state.tab!=='audit'||!select)return;
  pollingAudit=true;
  try{
    const cabinet=state.auditCabinet;
    const rows=await api('/admin/sessions');
    if(select!==$('#audit-session')||cabinet!==state.auditCabinet)return;
    const previous=state.sessions.find(s=>s.id===state.sessionId);
    state.sessions=rows;
    const visible=rows.filter(s=>!cabinet||s.cabinet_id===cabinet);
    const current=visible.find(s=>s.id===state.sessionId);
    if(!current&&!state.sessionId&&visible.length){state.sessionId=visible[0].id;await auditPage();return;}
    select.innerHTML='<option value="">请选择会话</option>'+visible.map(s=>`<option value="${esc(s.id)}" ${s.id===state.sessionId?'selected':''}>${esc(s.cabinet_id)} / ${time(s.created_at)} / ${esc(names[s.state]||s.state)}</option>`).join('');
    if(current&&previous&&current.state!==previous.state&&!$('#audit-form')&&!$('#cancel-unpaid'))await loadAudit();
  } catch(_){ /* 网络恢复后下一轮继续，保留已显示的会话与审核输入。 */ }
  finally{pollingAudit=false;}
},3000);
function fail(e){toast(e.message || String(e));console.error(e);}
let busy=false;
async function act(fn){if(busy)return;busy=true;try{await fn();}catch(e){fail(e);}finally{busy=false;}}
$('#login-form').onsubmit=async e=>{e.preventDefault();try{const data=await api('/auth/login','POST',{role:'ADMIN',password:$('#password').value},'');state.token=data.token;sessionStorage.setItem('adminToken',data.token);$('#login-error').textContent='';await render();}catch(err){$('#login-error').textContent=err.message;}};
$('#nav').onclick=e=>{const b=e.target.closest('[data-tab]');if(b){state.tab=b.dataset.tab;render().catch(fail);}};
$('#refresh').onclick=()=>render().catch(fail);
$('#logout').onclick=()=>{sessionStorage.removeItem('adminToken');state.token=null;showLogin();};
$('#dialog-close').onclick=()=>$('#detail-dialog').close();
render().catch(fail);
