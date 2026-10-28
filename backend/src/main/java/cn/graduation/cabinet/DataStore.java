package cn.graduation.cabinet;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.*;
import static cn.graduation.cabinet.Models.*;

/** JdbcTemplate负责SQL与参数绑定，所有用户输入均通过?传参，避免拼接SQL。 */
@Repository
public class DataStore {
    private final JdbcTemplate db;
    /** 通过方法访问实际目标对象，避免@Repository代理上的字段为空。 */
    public JdbcTemplate jdbc() { return db; }
    public DataStore(JdbcTemplate db) { this.db = db; }
    public List<Product> products() {
        return db.query("SELECT * FROM products ORDER BY id", (rs,n) -> new Product(rs.getString("id"),
            rs.getString("name"),rs.getInt("price_cents"),rs.getInt("weight_grams"),
            rs.getInt("threshold_qty"),rs.getBoolean("enabled")));
    }
    public Product product(String id) {
        return products().stream().filter(p -> p.id().equals(id)).findFirst()
            .orElseThrow(() -> new BusinessException("商品不存在"));
    }
    public Map<String,Object> one(String sql, Object... args) {
        List<Map<String,Object>> rows = db.queryForList(sql,args);
        if(rows.isEmpty()) throw new BusinessException("记录不存在");
        return rows.get(0);
    }
    /** 行级锁使两个同时到达的开柜/支付请求串行，事务结束自动释放。 */
    public Map<String,Object> lockSession(String id) {
        return one("SELECT * FROM shopping_sessions WHERE id=? FOR UPDATE",id);
    }
    public static String id() { return UUID.randomUUID().toString(); }
    public static long now() { return System.currentTimeMillis(); }
    public void log(String cabinet, String product, String kind, int quantity, String reference) {
        db.update("INSERT INTO inventory_logs VALUES(?,?,?,?,?,?,?)",id(),cabinet,product,kind,quantity,reference,now());
    }
}
