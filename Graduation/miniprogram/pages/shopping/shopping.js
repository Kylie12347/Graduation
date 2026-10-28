const { request, money, states, message } = require('../../utils/api');
Page({
  data: { session: null, products: [], productNames: [], busy: false, error: '', resolution: null, quantity: '1', note: '', directionIndex: 0, productIndex: 0, directions: ['取出', '放回'] },
  onLoad(options) { this.id = options.id; },
  onShow() {
    this.visible = true;
    this.refresh();
    // 轮询模拟实时消息；onHide/onUnload清理，避免离开页面后继续请求。
    this.timer = setInterval(() => this.refresh(true), 2500);
  },
  onHide() { this.visible = false; clearInterval(this.timer); },
  onUnload() { this.visible = false; clearInterval(this.timer); },
  async refresh(quiet = false) {
    if (!this.id || this.fetching) return;
    this.fetching = true;
    try {
      const session = await request('/sessions/' + this.id);
      const products = this.data.products.length ? this.data.products : await request('/products');
      session.label = states[session.state];
      session.items = session.items.map(i => ({ ...i, price: money(i.unitPriceCents), subtotal: money(i.subtotalCents) }));
      session.total = money(session.items.reduce((sum, i) => sum + i.subtotalCents, 0));
      session.events = session.events.map(e => ({ ...e, label: states[e.status], time: new Date(e.created_at).toLocaleTimeString(), candidates: e.result.candidates.map(c => c.name + '×' + c.quantity).join('，') || '无匹配候选' }));
      if (session.order) {
        session.order.amount = money(session.order.total_cents);
        session.order.items = session.order.items.map(i => ({ ...i, price: money(i.unitPriceCents), subtotal: money(i.subtotalCents) }));
      }
      if (this.visible) this.setData({ session, products, productNames: products.map(p => p.name + ' / ' + p.id), error: '' });
    } catch (e) {
      if (this.visible) this.setData({ error: e.message });
      if (!quiet) message(e);
    } finally { this.fetching = false; }
  },
  async run(action) {
    if (this.data.busy) return;
    this.setData({ busy: true });
    try { await action(); await this.refresh(); }
    catch (e) { message(e); }
    finally { this.setData({ busy: false }); }
  },
  close() { this.run(() => request('/sessions/' + this.id + '/close', 'POST')); },
  resolve(e) {
    const event = this.data.session.events.find(x => x.id === e.currentTarget.dataset.id);
    const candidate = event.result.candidates[0];
    const productIndex = candidate ? Math.max(0, this.data.products.findIndex(p => p.id === candidate.productId)) : 0;
    this.setData({ resolution: event, productIndex, quantity: String(candidate ? candidate.quantity : 1), directionIndex: event.result.direction === 'TAKE' ? 0 : 1, note: '' });
  },
  chooseProduct(e) { this.setData({ productIndex: Number(e.detail.value) }); },
  chooseDirection(e) { this.setData({ directionIndex: Number(e.detail.value) }); },
  setQuantity(e) { this.setData({ quantity: e.detail.value }); },
  setNote(e) { this.setData({ note: e.detail.value }); },
  dismissResolution() { this.setData({ resolution: null }); },
  saveResolution() {
    const quantity = Number(this.data.quantity);
    if (!Number.isInteger(quantity) || quantity < 1 || quantity > 10 || !this.data.note.trim()) return message(new Error('请输入1—10的整数数量，并填写确认说明'));
    this.run(async () => {
      await request(`/sessions/${this.id}/events/${this.data.resolution.id}/resolve`, 'POST', {
        productId: this.data.products[this.data.productIndex].id, quantity,
        direction: this.data.directionIndex === 0 ? 'TAKE' : 'RETURN', note: this.data.note.trim()
      });
      this.setData({ resolution: null });
    });
  },
  confirmList() { this.run(() => request('/orders/' + this.data.session.order_id + '/confirm', 'POST')); },
  pay(e) {
    const success = e.currentTarget.dataset.success === 'true';
    // 每次尝试有独立幂等键；如果重发同一次请求，应复用该键，后端不会重复记账。
    const requestKey = 'wx-' + Date.now() + '-' + Math.random().toString(36).slice(2);
    this.run(async () => {
      await request('/orders/' + this.data.session.order_id + '/pay', 'POST', { requestKey, success });
      wx.showToast({ title: success ? '模拟支付成功' : '模拟失败，可重试', icon: success ? 'success' : 'none' });
    });
  },
  cancel() {
    wx.showModal({ title: '取消订单', content: '有商品在柜外时，请管理员先在电脑面板执行“全部归还并取消”。确认尝试取消？', success: res => {
      if (res.confirm) this.run(() => request('/orders/' + this.data.session.order_id + '/cancel', 'POST'));
    } });
  },
  refreshTap() { this.refresh(); },
  home() { wx.switchTab({ url: '/pages/home/home' }); }
});
