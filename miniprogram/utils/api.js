/**
 * wx.request是微信平台API，不是浏览器fetch。
 * 用Promise封装success/fail回调后，页面可用async/await按顺序执行请求。
 * 同一时刻只发送一次登录请求，避免多个页面各自生成不同消费者身份。
 */
let loginPromise = null;
function raw(path, method, data, token) {
  return new Promise((resolve, reject) => {
    wx.request({
      url: getApp().globalData.baseUrl + path, method, data,
      timeout: 15000,
      header: { 'content-type': 'application/json', 'Cache-Control': 'no-cache', Authorization: 'Bearer ' + (token || '') },
      success(res) {
        if (res.statusCode >= 200 && res.statusCode < 300) resolve(res.data);
        else {
          if (res.statusCode === 401) {
            getApp().globalData.token = '';
            wx.removeStorageSync('consumerToken');
          }
          reject(new Error(res.data.message || '服务器请求失败'));
        }
      },
      fail() { reject(new Error('连接失败，请启动IDEA后端，并检查本地服务地址和不校验合法域名设置')); }
    });
  });
}
async function login() {
  if (getApp().globalData.token) return;
  if (!loginPromise) {
    loginPromise = raw('/auth/login', 'POST', { role: 'CONSUMER' }).then(data => {
      getApp().globalData.token = data.token;
      wx.setStorageSync('consumerToken', data.token);
    }).finally(() => { loginPromise = null; });
  }
  await loginPromise;
}
async function request(path, method = 'GET', data) {
  await login();
  // 不对写请求自动重试，避免网络波动时重复取放或创建订单。
  return raw(path, method, data, getApp().globalData.token);
}
const money = cents => (Number(cents || 0) / 100).toFixed(2);
const states = { OPEN: '购物中', REVIEW: '待确认清单', UNPAID: '待支付', PAID: '已支付', CANCELLED: '已取消', CONFIRMED: '自动确认', PENDING: '待人工确认', RESOLVED: '人工已确认' };
function message(error) { wx.showModal({ title: '操作提示', content: error.message || String(error), showCancel: false }); }
module.exports = { request, money, states, message };
