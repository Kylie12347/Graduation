/**
 * App()注册整个小程序，只执行一次；Page()注册具体页面。
 * globalData在各页面共享，通过getApp().globalData访问。
 * 开发者工具模拟器与后端在同一台电脑时使用127.0.0.1。
 * 真机调试须改为电脑局域网IP，并将后端SERVER_ADDRESS设为0.0.0.0。
 */
App({
  globalData: { baseUrl: 'http://127.0.0.1:8080/api' },
  onLaunch() {
    // 同步缓存API以Sync结尾；仅保存演示身份令牌，不保存密码或真实微信信息。
    this.globalData.token = wx.getStorageSync('consumerToken') || '';
  }
});
