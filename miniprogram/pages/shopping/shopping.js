const { request, money, states, message } = require('../../utils/api');
const { imageFor } = require('../../utils/storefront');
Page({
  data: { session: null, pending: [], reviewed: [], busy: false, error: '' },
  /** 每次进入页面都初始化实例标记，避免开发工具重载或再次购物沿用上次退出状态。 */
  onLoad(options = {}) {
    this.id = options.id;
    this.readOnly = options.readOnly === '1';
    this.exiting = false;
    this.completed = false;
    this.fetching = false;
    this.revision = 0;
    this.setData({ session: null, pending: [], reviewed: [], busy: false, error: '' });
  },
  onShow() { this.visible = true; this.refresh(); clearInterval(this.timer); this.timer = setInterval(() => this.refresh(true), 2500); },
  onHide() { this.visible = false; clearInterval(this.timer); },
  onUnload() { this.visible = false; clearInterval(this.timer); },
  async refresh(quiet = false) {
    if (!this.id || this.fetching || this.exiting || this.completed || this.data.busy) return;
    this.fetching = true;
    const revision = this.revision || 0;
    const sessionId = this.id;
    try {
      const raw = await request('/sessions/' + encodeURIComponent(sessionId));
      // 写操作开始后，旧轮询结果不再有权覆盖页面。
      if (revision !== (this.revision || 0) || this.completed || this.exiting || sessionId !== this.id) return;
      if (this.visible && raw.state === 'PAID' && !this.readOnly) {
        this.finishCheckout();
        return;
      }
      // 后端保留完整证据用于管理端审计；消费者视图只映射商品与待确认项目。
      const decorate = i => ({ ...i, image: imageFor(i.productId), price: money(i.unitPriceCents), subtotal: money(i.subtotalCents) });
      const session = { doorOpen: raw.doorOpen, cabinetLocked: raw.cabinetLocked, id: raw.id, cabinet_id: raw.cabinet_id, state: raw.state, label: states[raw.state], order_id: raw.order_id,
        pendingCount: raw.pendingCount, basketError: !!raw.basketError, items: raw.items.map(decorate),
        total: money(raw.items.reduce((sum, i) => sum + i.subtotalCents, 0)), order: null };
      if (raw.order) session.order = { amount: money(raw.order.total_cents), items: raw.order.items.map(decorate) };
      const pending = raw.events.filter(e => e.status === 'PENDING').map((e, index) => ({ id: e.id, title: '待确认商品 ' + (index + 1),
        hint: [...new Set(e.result.candidates.map(c => c.name))].join(' / ') || '请选择实际取放的商品' }));
      const reviewed = raw.events.filter(e => e.status === 'RESOLVED').map((e, index) => ({ id: e.id, title: '已核对项目 ' + (index + 1) }));
      if (this.visible) this.setData({ session, pending, reviewed, error: '' });
    } catch (e) { if (this.visible) this.setData({ error: e.message }); if (!quiet) message(e); }
    finally { this.fetching = false; }
  },
  async run(action) {
    if (this.data.busy || this.completed || this.exiting) return;
    this.revision = (this.revision || 0) + 1;
    this.setData({ busy: true });
    try { await action(); } catch (e) { message(e); }
    finally {
      this.setData({ busy: false });
      if (!this.completed && !this.exiting) await this.refresh();
    }
  },
  close() { this.run(() => request('/sessions/' + this.id + '/close', 'POST')); },
  resolve(e) {
    wx.navigateTo({ url: '/pages/resolve/resolve?sessionId=' + encodeURIComponent(this.id) + '&eventId=' + encodeURIComponent(e.currentTarget.dataset.id) });
  },
  confirmList() { this.run(() => request('/orders/' + this.data.session.order_id + '/confirm', 'POST')); },
  pay(e) {
    const success = e.currentTarget.dataset.success === 'true';
    const requestKey = 'wx-' + Date.now() + '-' + Math.random().toString(36).slice(2);
    return this.run(async () => {
      const order = await request('/orders/' + this.data.session.order_id + '/pay', 'POST', { requestKey, success });
      // 以服务端PAID为准，不能只按按钮上的success判断付款成功。
      if (order.state === 'PAID') {
        // 先落下终态，再导航；即使路由失败也不能继续显示或再次支付旧订单。
        this.setData({ session: { ...this.data.session, state: 'PAID', label: states.PAID || '已支付' }, pending: [], reviewed: [] });
        this.finishCheckout();
      }
      else wx.showToast({ title: '支付未成功，请重试', icon: 'none' });
    });
  },
  /** 支付事务已完成锁柜，清理本次绑定并退出购物页；历史订单仍可单独查看。 */
  finishCheckout() {
    if (this.exiting) return;
    this.exiting = true;
    this.completed = true;
    if (this.data.session) this.setData({ session: { ...this.data.session, state: 'PAID', label: states.PAID || '已支付' }, pending: [], reviewed: [] });
    this.revision = (this.revision || 0) + 1;
    clearInterval(this.timer);
    const app = getApp().globalData;
    // 仅记录后端已确认支付成功的会话，用于拦截在途首页请求中的旧状态。
    app.completedSessionIds = [...new Set([...(app.completedSessionIds || []), this.id])].slice(-100);
    app.boundCabinetId = '';
    app.pendingCabinetId = '';
    app.previewCabinetId = '';
    app.checkoutNotice = '已支付并锁柜';
    wx.switchTab({
      url: '/pages/home/home',
      fail: () => {
        // switchTab失败时用reLaunch清空页面栈；两者都失败仍保持已支付终态。
        wx.reLaunch({ url: '/pages/home/home', fail: () => {
          this.exiting = false;
          this.setData({ error: '支付已完成，柜体已锁定，请点击返回首页。' });
        } });
      }
    });
  },
  cancel() {
    wx.showModal({ title: '取消订单', content: '取消前请先归还全部商品。确认申请取消？', success: res => {
      if (res.confirm) this.run(() => request('/orders/' + this.data.session.order_id + '/cancel', 'POST'));
    } });
  },
  refreshTap() { this.refresh(); },
  home() { wx.switchTab({ url: '/pages/home/home' }); }
});
