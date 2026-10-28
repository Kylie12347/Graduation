package cn.graduation.cabinet;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static cn.graduation.cabinet.Models.*;

/** 虚拟单品标签库。位置是真值，仅供仿真器使用；融合器只能收到读卡快照。 */
@Service
public class RfidService {
    private final DataStore s;
    public RfidService(DataStore s) { this.s=s; }

    /** 调用方持有柜体行锁。旧数据库只迁移一次，不删除原订单或库存。 */
    public void ensure(String cabinet) {
        if(s.jdbc().queryForObject("SELECT COUNT(*) FROM rfid_initialized WHERE cabinet_id=?",Integer.class,cabinet)>0) return;
        var c=s.one("SELECT * FROM cabinets WHERE id=?",cabinet);
        String active=(String)c.get("active_session");
        for(var row:s.jdbc().queryForList("SELECT * FROM stocks WHERE cabinet_id=?",cabinet))
            add(cabinet,(String)row.get("product_id"),((Number)row.get("on_hand")).intValue(),null);
        // 旧版正在购物的柜外商品也需要标签，否则升级后无法放回。
        if(active!=null) for(var e:SimulatorService.held(s,active).entrySet())
            add(cabinet,e.getKey(),e.getValue(),active);
        s.jdbc().update("INSERT INTO rfid_initialized VALUES(?)",cabinet);
    }
    public void add(String cabinet,String sku,int quantity,String heldBy) {
        for(int i=0;i<quantity;i++) s.jdbc().update(
            "INSERT INTO rfid_items(epc,cabinet_id,product_id,location,held_session) VALUES(?,?,?,?,?)",
            "EPC"+UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT),
            cabinet,sku,heldBy==null?"IN_CABINET":"OUTSIDE",heldBy);
    }
    public List<String> scan(String cabinet) {
        return s.jdbc().queryForList("SELECT epc FROM rfid_items WHERE cabinet_id=? AND location='IN_CABINET' ORDER BY epc",String.class,cabinet);
    }
    public Map<String,String> bindings(String cabinet) {
        Map<String,String> result=new LinkedHashMap<>();
        for(var row:s.jdbc().queryForList("SELECT epc,product_id FROM rfid_items WHERE cabinet_id=? ORDER BY epc",cabinet))
            result.put((String)row.get("epc"),(String)row.get("product_id"));
        return result;
    }
    /** 放回的是本会话取出的同一件商品，标签不重新生成。 */
    public List<String> move(String cabinet,String session,String sku,int quantity,boolean take) {
        var rows=s.jdbc().queryForList("SELECT epc FROM rfid_items WHERE cabinet_id=? AND product_id=? AND location=?"+
            (take?"":" AND held_session=?")+" ORDER BY epc FOR UPDATE",
            take?new Object[]{cabinet,sku,"IN_CABINET"}:new Object[]{cabinet,sku,"OUTSIDE",session});
        if(rows.size()<quantity) throw new BusinessException("虚拟标签数量与库存不一致，请核对标签库");
        List<String> moved=new ArrayList<>();
        for(int i=0;i<quantity;i++) {
            String epc=(String)rows.get(i).get("epc"); moved.add(epc);
            s.jdbc().update("UPDATE rfid_items SET location=?,held_session=? WHERE epc=?",take?"OUTSIDE":"IN_CABINET",take?session:null,epc);
        }
        return moved;
    }
    /** 纯集合差算法，不接收操作方向、商品答案或实际移动的标签列表。重复读数按集合去重。 */
    public static Map<String,Integer> delta(RfidObservation observation) {
        Set<String> before=new LinkedHashSet<>(observation.beforeTags());
        Set<String> after=new LinkedHashSet<>(observation.afterTags());
        Map<String,Integer> result=new LinkedHashMap<>();
        for(String epc:before) if(!after.contains(epc)) result.merge(sku(observation,epc),-1,Integer::sum);
        for(String epc:after) if(!before.contains(epc)) result.merge(sku(observation,epc),1,Integer::sum);
        result.values().removeIf(n->n==0);
        return result;
    }
    private static String sku(RfidObservation o,String epc) {
        String sku=o.tagProducts().get(epc);
        if(sku==null) throw new BusinessException("RFID存在未登记标签："+epc);
        return sku;
    }
    @Transactional
    public List<Map<String,Object>> items(String cabinet) {
        s.one("SELECT * FROM cabinets WHERE id=? FOR UPDATE",cabinet); ensure(cabinet);
        return s.jdbc().queryForList("SELECT * FROM rfid_items WHERE cabinet_id=? ORDER BY product_id,epc",cabinet);
    }
}
