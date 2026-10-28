const { request, message } = require('../../utils/api');
Page({
  data: { cabinets: [], loading: true, error: '' },
  onShow() { this.refresh(); },
  async onPullDownRefresh() { await this.refresh(); wx.stopPullDownRefresh(); },
  async refresh() {
    this.setData({ loading: true });
    try { const rows = await request('/cabinets'); this.setData({ cabinets: rows.map(c => ({ id: c.id, name: c.name, location: c.location, available: !c.active_session })), error: '' }); }
    catch(e) { this.setData({ error: e.message }); }
    finally { this.setData({ loading: false }); }
  },
  /** 查看商品不绑定开门权限：消费者仍须到柜体扫描它的二维码。 */
  browse(e) {
    getApp().globalData.previewCabinetId = e.currentTarget.dataset.id;
    wx.switchTab({ url: '/pages/home/home' });
  },
  refreshTap() { this.refresh(); }
});
