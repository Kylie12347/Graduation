const { request, money, states, message } = require('../../utils/api');
Page({
  data: { orders: [] },
  onShow() { this.refresh(); },
  async onPullDownRefresh() { await this.refresh(); wx.stopPullDownRefresh(); },
  async refresh() {
    try {
      const orders = await request('/orders');
      this.setData({ orders: orders.map(o => ({ ...o, label: states[o.state], amount: money(o.total_cents), shortId: o.id.slice(0, 8), date: new Date(o.created_at).toLocaleString() })) });
    } catch (e) { message(e); }
  },
  detail(e) { wx.navigateTo({ url: '/pages/shopping/shopping?id=' + e.currentTarget.dataset.session }); }
});
