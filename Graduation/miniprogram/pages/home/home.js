const { request, message, states } = require('../../utils/api');
/** 页面中的data会映射到WXML；必须用setData更新，直接赋值不会刷新界面。 */
Page({
  data: { cabinets: [], cabinetId: 'CAB001', active: null, loading: false },
  onShow() { this.refresh(); },
  async onPullDownRefresh() { await this.refresh(); wx.stopPullDownRefresh(); },
  async refresh() {
    try {
      const [cabinets, sessions] = await Promise.all([request('/cabinets'), request('/sessions')]);
      const active = sessions.find(s => !['PAID', 'CANCELLED'].includes(s.state));
      this.setData({ cabinets, active: active ? { ...active, label: states[active.state] } : null });
    } catch (e) { message(e); }
  },
  inputCabinet(e) { this.setData({ cabinetId: e.detail.value.trim() }); },
  /** bindtap触发事件后，data-id通过e.currentTarget.dataset.id取出。 */
  selectCabinet(e) { this.setData({ cabinetId: e.currentTarget.dataset.id }); },
  scan() {
    wx.scanCode({
      onlyFromCamera: false,
      success: res => {
        // 二维码可直接编码CAB001，或编码包含cabinetId=CAB001的URL。
        const match = res.result.match(/[?&]cabinetId=([A-Za-z0-9_-]+)/);
        const id = match ? match[1] : res.result.trim();
        if (!/^[A-Za-z0-9_-]{1,40}$/.test(id)) return message(new Error('二维码中没有有效柜体编号'));
        this.setData({ cabinetId: id });
      },
      fail: res => { if (!String(res.errMsg).includes('cancel')) message(new Error('扫码失败，可直接输入柜体编号')); }
    });
  },
  async open() {
    if (this.data.loading) return;
    if (!this.data.cabinetId) return message(new Error('请输入柜体编号'));
    this.setData({ loading: true });
    try {
      const session = await request('/sessions', 'POST', { cabinetId: this.data.cabinetId });
      wx.navigateTo({ url: '/pages/shopping/shopping?id=' + session.id });
    } catch (e) { message(e); }
    finally { this.setData({ loading: false }); }
  },
  resume() { wx.navigateTo({ url: '/pages/shopping/shopping?id=' + this.data.active.id }); }
});
