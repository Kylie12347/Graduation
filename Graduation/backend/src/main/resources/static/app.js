/*
 * 管理台使用浏览器原生JavaScript，不依赖npm。
 * const/let声明变量；async/await组织异步请求；模板字符串生成页面结构。
 * 所有服务器文本在插入innerHTML前都经过esc转义，避免商品名称被当作HTML执行。
 */
const $ = selector => document.querySelector(selector);
const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const money = cents => '¥' + (Number(cents || 0) / 100).toFixed(2);
const time = value => value ? new Date(value).toLocaleString('zh-CN', {timeZone:'Asia/Shanghai',hour12:false}) : '—';
const names = {OPEN:'购物中',REVIEW:'待确认清单',UNPAID:'待支付',PAID:'已支付',CANCELLED:'已取消',PENDING:'待人工确认',CONFIRMED:'自动确认',RESOLVED:'人工已确认',SUCCESS:'模拟支付成功',FAILED:'模拟支付失败',TAKE:'取出',RETURN:'放回',INITIAL:'初始库存',RESTOCK:'补货',SALE:'支付销售'};
const badge = status => `<span class="badge ${status==='PENDING'?'warn':status==='CANCELLED'?'red':''}">${esc(names[status] || status)}</span>`;
const state = {token:sessionStorage.getItem('adminToken'),tab:'dashboard',products:[],cabinets:[],stocks:[],sessions:[],sessionId:'',session:null};
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
function selectCabinets(){return state.cabinets.map(c=>`<option value="${esc(c.id)}">${esc(c.name)} / ${esc(c.id)}</option>`).join('');}
async function loadBase(){[state.products,state.cabinets,state.stocks,state.sessions]=await Promise.all([api('/products'),api('/cabinets'),api('/admin/stocks'),api('/admin/sessions')]);}
async function render(){
  if(!state.token) return showLogin();
  $('#login').classList.add('hidden');$('#workspace').classList.remove('hidden');
  const titles={dashboard:'经营概览',simulator:'虚拟售货柜',products:'商品与库存',orders:'订单与交易',logs:'库存流水'};
  $('#page-title').textContent=titles[state.tab];
  document.querySelectorAll('#nav button').forEach(b=>b.classList.toggle('active',b.dataset.tab===state.tab));
  await loadBase();
  if(state.tab==='dashboard') await dashboard();
  else if(state.tab==='simulator') await simulatorPage();
  else if(state.tab==='products') productsPage();
  else if(state.tab==='orders') await ordersPage();
  else await logsPage();
}
async function dashboard(date=''){
  const d=await api('/admin/dashboard'+(date?'?date='+date:''));
  const peak=Math.max(...d.hourlyRevenue,1);
  $('#content').innerHTML=`<section class="hero"><div><span class="eyebrow">EVERY TRANSACTION TELLS A STORY</span><p><strong>看得见的库存，算得清的每一单。</strong></p><p>按支付成功时间统计 · 上海时区 · 不计入失败和取消订单</p></div><label>查看营业日期<input id="business-date" type="date" value="${esc(d.date)}"></label></section>
    <section class="stats"><div class="stat"><span>当日营业额</span><strong>${money(d.revenueCents)}</strong><small>全部为模拟支付金额</small></div><div class="stat"><span>成功订单</span><strong>${d.paidOrders}<small> 单</small></strong><small>重复支付只记账一次</small></div><div class="stat"><span>售出商品</span><strong>${d.soldQuantity}<small> 件</small></strong><small>以消费者确认的订单为准</small></div><div class="stat"><span>需要补货</span><strong>${d.lowStock.length}<small> 项</small></strong><small>另有 ${d.pendingEvents} 个感知事件待确认</small></div></section>
    <div class="grid-two"><section class="panel"><div class="panel-head"><h2>24小时营业走势</h2><span class="subtle">元 / 小时</span></div><div class="chart">${d.hourlyRevenue.map((v,i)=>`<div class="chart-col" title="${i}时 ${money(v)}"><span class="bar" style="height:${Math.max(2,v/peak*135)}px"></span><small>${i%3===0?i+'时':''}</small></div>`).join('')}</div></section><section class="panel"><h2>补货提醒</h2>${d.lowStock.length?d.lowStock.map(r=>`<div class="alert-row"><b>${esc(r.name)}</b> · ${esc(r.cabinet_id)}<br>柜内剩余 ${r.on_hand} 件 / 预警线 ${r.threshold_qty} 件</div>`).join(''):'<p class="empty">库存充足，暂时无需补货</p>'}</section></div>
    <div class="grid-two"><section class="panel"><h2>当日商品销售</h2>${table(['商品','售出件数','销售金额'],d.productSales.map(r=>[esc(r.name),r.quantity,money(r.amountCents)]))}</section><section class="panel"><h2>各柜当日流水</h2>${table(['柜体','营业额'],Object.entries(d.cabinetRevenue).map(([id,value])=>[esc(id),money(value)]))}</section></div>
    <section class="panel"><h2>实时库存 · 累计销售</h2>${inventoryTable(d.stocks)}</section>`;
  $('#business-date').onchange=e=>dashboard(e.target.value).catch(fail);
}
function inventoryTable(rows){return table(['柜体 / 商品','实际柜内','待结算取出','累计已售','阈值','库存核对'],rows.map(r=>[`${esc(r.cabinet_id)} / ${esc(r.name)}`,`${r.on_hand} ${r.lowStock?'<span class="badge warn">需补货</span>':''}`,r.held,r.sold,r.threshold_qty,r.discrepancy?`<span class="error">差异 ${r.discrepancy} 件，需核对</span>`:'<span class="subtle">一致</span>']));}
async function simulatorPage(){
  const active=state.sessions.filter(s=>!['PAID','CANCELLED'].includes(s.state));
  if(!active.some(s=>s.id===state.sessionId)) state.sessionId=active[0]?.id || '';
  $('#content').innerHTML=`<section class="hero"><div><strong>真实柜体的全部操作，在这里模拟。</strong><p>消费者用小程序开柜；电脑面板负责取出、放回和关柜。每次操作一种商品，可设1—10件。</p></div><span class="badge">无硬件 · 无图像模型</span></section>
    <div class="cabinet-grid">${state.cabinets.map(c=>`<article class="cabinet-card"><h3>${esc(c.name)} <span class="badge ${c.active_session?'warn':''}">${c.active_session?'占用中':'可用'}</span></h3><p>${esc(c.id)} · ${esc(c.location)}<br>柜门：${c.door_open?'已打开':'已关闭'}</p>${!c.active_session?`<button data-demo-open="${esc(c.id)}">网页模拟消费者开柜</button>`:''}</article>`).join('')}</div>
    <section class="panel"><div class="panel-head"><h2>购物会话</h2><button id="refresh-sessions">刷新会话</button></div><select id="session-select"><option value="">请选择正在购物的会话</option>${active.map(s=>`<option value="${esc(s.id)}" ${state.sessionId===s.id?'selected':''}>${esc(s.cabinet_id)} / ${esc(s.id.slice(0,8))} / ${esc(names[s.state])}</option>`).join('')}</select><p class="subtle">小程序开柜后点击刷新；演示按钮创建独立消费者身份，不会替你点击商品购买。</p></section>
    <section class="panel"><h2>感知异常配置</h2><div class="form-grid"><label>每次操作件数<input id="sim-qty" type="number" value="1" min="1" max="10"></label><label>重量误差（克，带正负）<input id="sim-error" type="number" value="0" min="-500" max="500"></label><label>RFID错误识别为<select id="sim-rfid"><option value="">正常识别</option>${selectProducts()}</select></label><div><label class="check"><input id="sim-miss" type="checkbox">RFID漏读（优先于错误SKU）</label><label class="check"><input id="sim-vision" type="checkbox">视觉发现差异</label></div></div><p class="subtle">视觉只提供“有差异 / 无差异”结果。融合模块只读取生成的观测，不读取取放操作答案。</p></section>
    <section class="panel"><div class="panel-head"><h2>虚拟货架</h2><div class="row"><button id="close-door" class="primary">关柜生成清单</button><button id="return-cancel" class="danger">全部归还并取消</button></div></div><div id="shelf" class="shelf"></div></section>
    <section class="panel"><h2>三步融合过程</h2><div id="events"></div></section><section class="panel"><h2>本次购物清单</h2><div id="basket"></div></section>`;
  $('#session-select').onchange=async e=>{state.sessionId=e.target.value;await updateSession();};
  $('#refresh-sessions').onclick=()=>render().catch(fail);
  $('#close-door').onclick=()=>act(async()=>{if(!state.sessionId)throw new Error('请先选择会话');await api(`/sessions/${state.sessionId}/close`,'POST');await updateSession();toast('已关柜，请在小程序确认清单并模拟支付');});
  $('#return-cancel').onclick=()=>act(async()=>{if(!state.sessionId)throw new Error('请先选择会话');if(!confirm('将实际归还本会话全部柜外商品并取消订单，是否继续？'))return;await api(`/admin/sessions/${state.sessionId}/return-and-cancel`,'POST');state.sessionId='';await render();toast('全部商品已归还，订单已取消');});
  document.querySelectorAll('[data-demo-open]').forEach(b=>b.onclick=()=>act(async()=>{const guest=await api('/auth/login','POST',{role:'CONSUMER'},'');const session=await api('/sessions','POST',{cabinetId:b.dataset.demoOpen},guest.token);sessionStorage.setItem('demoConsumerToken',guest.token);state.sessionId=session.id;await render();}));
  await updateSession();
}
async function updateSession(){
  state.session=state.sessionId?await api('/sessions/'+state.sessionId):null;
  state.stocks=await api('/admin/stocks');
  const current=state.session;
  const rows=state.stocks.filter(r=>r.cabinet_id===(current?.cabinet_id || state.cabinets[0]?.id));
  const icons={COLA:'🥤',WATER:'💧',TEA:'🍵',JUICE:'🍊',BISCUIT:'🍪'};
  $('#shelf').innerHTML=rows.map(r=>`<article class="product-card"><span class="product-icon">${icons[r.product_id]||'📦'}</span><h3>${esc(r.name)}</h3><span class="price">${money(r.price_cents)}</span><p class="subtle">${r.weight_grams}克 / 柜内 ${r.on_hand} 件<br>本会话柜外 ${r.held} 件</p><div class="row"><button data-product="${esc(r.product_id)}" data-direction="TAKE" ${current?.state==='OPEN'?'':'disabled'}>取出 −</button><button data-product="${esc(r.product_id)}" data-direction="RETURN" ${current?.state==='OPEN'?'':'disabled'}>放回 ＋</button></div></article>`).join('');
  $('#close-door').disabled=current?.state!=='OPEN';$('#return-cancel').disabled=!current || ['PAID','CANCELLED'].includes(current.state);
  document.querySelectorAll('[data-direction]').forEach(b=>b.onclick=()=>act(async()=>{await api(`/admin/sessions/${state.sessionId}/simulate`,'POST',{productId:b.dataset.product,direction:b.dataset.direction,quantity:Number($('#sim-qty').value),weightError:Number($('#sim-error').value),rfidMiss:$('#sim-miss').checked,rfidOverride:$('#sim-rfid').value,visionDifferent:$('#sim-vision').checked});await updateSession();toast('操作已执行，三类感知数据已生成');}));
  $('#events').innerHTML=current?.events.length?current.events.map(event=>`<details class="event" open><summary><span>${esc(event.result.reason)}</span>${badge(event.status)}</summary><div class="steps">${event.result.steps.map(esc).join('<br>')}</div><pre>观测：${esc(JSON.stringify(event.signal))}\n候选：${esc(event.result.candidates.map(c=>c.name+'×'+c.quantity).join('，')||'无')}</pre>${event.status==='RESOLVED'?`<p class="subtle">人工确认：${esc(event.resolved_product)} × ${event.resolved_qty} · ${esc(event.resolution_note)}</p>`:''}</details>`).join(''):'<p class="empty">取出或放回商品后，可查看重量→RFID→视觉的完整判断过程</p>';
  $('#basket').innerHTML=current?`<p>${badge(current.state)} <span class="subtle">${current.pendingCount}个待确认事件 · 请由小程序处理</span></p>${current.basketError?`<p class="error">${esc(current.basketError)}</p>`:''}${table(['商品','净取出','单价','小计'],current.items.map(i=>[esc(i.name),i.quantity,money(i.unitPriceCents),money(i.subtotalCents)]))}<p class="subtle">消费者确认后锁定成交单价。后台仅生成购物过程，小程序完成确认和支付。</p>`:'<p class="empty">请先通过小程序或演示按钮开柜</p>';
}
function productsPage(){
  $('#content').innerHTML=`<section class="panel"><div class="panel-head"><h2>商品资料</h2><span class="subtle">金额单位：分；重量单位：克</span></div>${table(['编号','商品','价格','重量','预警线','状态','操作'],state.products.map(p=>[esc(p.id),esc(p.name),money(p.priceCents),p.weightGrams,p.thresholdQty,p.enabled?'在售':'已下架',`<button data-edit="${esc(p.id)}">编辑</button>`]))}<hr><form id="product-form"><div class="form-grid"><label>SKU编号<input name="id" required maxlength="40" pattern="[A-Za-z0-9_-]+" placeholder="如 MILK"></label><label>商品名称<input name="name" required maxlength="100"></label><label>售价（分）<input name="priceCents" type="number" value="500" min="1" required></label><label>标称重量（克）<input name="weightGrams" type="number" value="250" min="1" required></label><label>补货预警线<input name="thresholdQty" type="number" value="3" min="0" required></label><label>销售状态<select name="enabled"><option value="true">在售</option><option value="false">下架</option></select></label></div><button class="primary">新增 / 保存商品</button><span class="subtle"> 同一编号为编辑；下架保留历史记录。购物期间禁止改价。</span></form></section>
    <section class="panel"><h2>库存与补货</h2>${inventoryTable(state.stocks)}<form id="restock-form"><div class="form-grid three"><label>柜体<select name="cabinetId">${selectCabinets()}</select></label><label>商品<select name="productId">${selectProducts()}</select></label><label>补货数量<input name="quantity" type="number" value="10" min="1" max="1000" required></label></div><button class="primary">确认补货入库</button></form></section>
    <section class="panel"><h2>新增虚拟柜体</h2><form id="cabinet-form"><div class="form-grid three"><label>柜体编号<input name="id" placeholder="CAB002" required pattern="[A-Za-z0-9_-]{1,40}"></label><label>柜体名称<input name="name" required maxlength="100"></label><label>位置<input name="location" required maxlength="200"></label></div><button>创建柜体</button></form></section>`;
  $('#product-form').onsubmit=e=>{e.preventDefault();act(async()=>{const data=Object.fromEntries(new FormData(e.target));['priceCents','weightGrams','thresholdQty'].forEach(k=>data[k]=Number(data[k]));data.enabled=data.enabled==='true';await api('/admin/products','POST',data);await render();toast('商品已保存');});};
  document.querySelectorAll('[data-edit]').forEach(b=>b.onclick=()=>{const p=state.products.find(p=>p.id===b.dataset.edit);Object.entries(p).forEach(([k,v])=>{if($('#product-form').elements[k])$('#product-form').elements[k].value=String(v);});$('#product-form').scrollIntoView({behavior:'smooth'});});
  $('#restock-form').onsubmit=e=>{e.preventDefault();act(async()=>{const data=Object.fromEntries(new FormData(e.target));data.quantity=Number(data.quantity);await api('/admin/restock','POST',data);await render();toast('补货完成，库存与入库流水已更新');});};
  $('#cabinet-form').onsubmit=e=>{e.preventDefault();act(async()=>{await api('/admin/cabinets','POST',Object.fromEntries(new FormData(e.target)));await render();toast('虚拟柜体已创建，请补货后使用');});};
}
async function ordersPage(){
  const orders=await api('/admin/orders');
  $('#content').innerHTML=`<section class="panel"><h2>最近200笔订单</h2>${table(['订单编号','柜体','状态','金额','创建时间','支付时间','详情'],orders.map(o=>[esc(o.id.slice(0,8)),esc(o.cabinet_id),badge(o.state),money(o.total_cents),time(o.created_at),time(o.paid_at),`<button data-order="${esc(o.id)}">查看交易</button>`]))}</section>`;
  document.querySelectorAll('[data-order]').forEach(b=>b.onclick=()=>act(async()=>{const data=await api('/orders/'+b.dataset.order);$('#dialog-text').textContent=JSON.stringify(data,null,2);$('#detail-dialog').showModal();}));
}
async function logsPage(){const rows=await api('/admin/inventory-logs');$('#content').innerHTML=`<section class="panel"><h2>最近200笔库存变化</h2><p class="subtle">取出/放回改变物理库存，支付销售只增加销量；二者分别记录。</p>${table(['时间','柜体','SKU','类型','件数','关联编号'],rows.map(r=>[time(r.created_at),esc(r.cabinet_id),esc(r.product_id),badge(r.kind),r.quantity,esc(r.reference_id?.slice(0,8)||'—')]))}</section>`;}
function fail(e){toast(e.message || String(e));console.error(e);}
let busy=false;
async function act(fn){if(busy)return;busy=true;try{await fn();}catch(e){fail(e);}finally{busy=false;}}
$('#login-form').onsubmit=async e=>{e.preventDefault();try{const data=await api('/auth/login','POST',{role:'ADMIN',password:$('#password').value},'');state.token=data.token;sessionStorage.setItem('adminToken',data.token);$('#login-error').textContent='';await render();}catch(err){$('#login-error').textContent=err.message;}};
$('#nav').onclick=e=>{const b=e.target.closest('[data-tab]');if(b){state.tab=b.dataset.tab;render().catch(fail);}};
$('#refresh').onclick=()=>render().catch(fail);
$('#logout').onclick=()=>{sessionStorage.removeItem('adminToken');state.token=null;showLogin();};
$('#dialog-close').onclick=()=>$('#detail-dialog').close();
render().catch(fail);
