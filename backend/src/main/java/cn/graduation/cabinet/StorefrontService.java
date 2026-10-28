package cn.graduation.cabinet;

import org.springframework.stereotype.Service;
import java.util.*;

/** 消费者货架只返回本柜在售商品、售价和柜内余量，不暴露传感器或运营对账数据。 */
@Service
public class StorefrontService {
    private final DataStore s;
    public StorefrontService(DataStore s) { this.s=s; }
    public Map<String,Object> catalog(String cabinetId) {
        var cabinet=s.one("SELECT c.id,c.name,c.location,c.active_session,COALESCE(l.locked,TRUE) AS locked FROM cabinets c LEFT JOIN cabinet_locks l ON l.cabinet_id=c.id WHERE c.id=?",cabinetId);
        var goods=s.jdbc().queryForList("SELECT p.id,p.name,p.price_cents AS price_cents,st.on_hand AS stock FROM stocks st JOIN products p ON p.id=st.product_id WHERE st.cabinet_id=? AND p.enabled=TRUE ORDER BY p.id",cabinetId);
        return Map.of("cabinet",Map.of("id",cabinet.get("id"),"name",cabinet.get("name"),"location",cabinet.get("location"),"available",cabinet.get("active_session")==null,"locked",cabinet.get("locked")),"products",goods);
    }
}
