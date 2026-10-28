const { request, money, message } = require('../../utils/api');
const { imageFor } = require('../../utils/storefront');
/** 独立页面使用点击卡片选商品、按钮改数量，不使用picker滚轮或弹层。 */
Page({
  data: { products: [], selectedId: '', quantity: 1, direction: 'TAKE', busy: false, loading: true, editable: false, error: '' },
  onLoad(options) { this.sessionId = options.sessionId; this.eventId = options.eventId; },
  onShow() { this.load(); },
  async load() {
    this.setData({ loading: true });
    try {
      const session = await request('/sessions/' + encodeURIComponent(this.sessionId));
      const event = session.events.find(e => e.id === this.eventId);
      if (!event || event.status === 'CONFIRMED') throw new Error('该项目无需人工确认，请返回购物清单');
      const catalog = await request('/cabinets/' + encodeURIComponent(session.cabinet_id) + '/catalog');
      const candidateIds = new Set(event.result.candidates.map(c => c.productId));
      const products = catalog.products.map(p => ({ id: p.id, name: p.name, price: money(p.price_cents), image: imageFor(p.id), recommended: candidateIds.has(p.id) }));
      // 人工已确认的下架商品仍可核对，不能因商品下架丢失已有答案。
      if (event.status === 'RESOLVED' && !products.some(p => p.id === event.resolved_product)) {
        const all = await request('/products'); const p = all.find(p => p.id === event.resolved_product);
        if (p) products.push({ id: p.id, name: p.name, price: money(p.priceCents), image: imageFor(p.id), recommended: false });
      }
      products.sort((a,b) => Number(b.recommended) - Number(a.recommended));
      this.setData({ products, selectedId: event.status === 'RESOLVED' ? event.resolved_product : '',
        quantity: event.status === 'RESOLVED' ? event.resolved_qty : 1,
        direction: event.status === 'RESOLVED' ? event.resolved_direction : event.result.direction,
        editable: session.state === 'REVIEW', error: session.state === 'REVIEW' ? '' : '当前清单已更新，请返回查看。' });
    } catch(e) { this.setData({ error: e.message, editable: false }); }
    finally { this.setData({ loading: false }); }
  },
  selectProduct(e) { if (this.data.editable) this.setData({ selectedId: e.currentTarget.dataset.id }); },
  selectDirection(e) { if (this.data.editable) this.setData({ direction: e.currentTarget.dataset.direction }); },
  decrease() { if (this.data.editable && this.data.quantity > 1) this.setData({ quantity: this.data.quantity - 1 }); },
  increase() { if (this.data.editable && this.data.quantity < 10) this.setData({ quantity: this.data.quantity + 1 }); },
  async save() {
    if (this.data.busy || !this.data.editable || !this.data.selectedId) return;
    const product = this.data.products.find(p => p.id === this.data.selectedId);
    if (!product) return;
    this.setData({ busy: true });
    try {
      await request('/sessions/' + this.sessionId + '/events/' + this.eventId + '/resolve', 'POST', {
        productId: product.id, quantity: this.data.quantity, direction: this.data.direction,
        // 消费者不必输入技术说明，服务端仍保留完整人工确认审计记录。
        note: '消费者核对：' + (this.data.direction === 'TAKE' ? '取出' : '放回') + product.name + this.data.quantity + '件'
      });
      wx.showToast({ title: '已确认', icon: 'success' });
      wx.navigateBack();
    } catch(e) { message(e); }
    finally { this.setData({ busy: false }); }
  },
  back() { wx.navigateBack(); },
  retry() { this.load(); }
});
