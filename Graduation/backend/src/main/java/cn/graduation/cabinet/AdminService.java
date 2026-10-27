package cn.graduation.cabinet;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.core.type.TypeReference;
import java.time.*;
import java.util.*;
import static cn.graduation.cabinet.Models.*;
import static cn.graduation.cabinet.DataStore.*;

/** 管理业务：商品、柜体、补货、库存对账、按上海时区统计已支付营业流水。 */
@Service
public class AdminService {
    private final DataStore s;
    private final JsonCodec json;
    private final ZoneId zone;
    public AdminService(DataStore s,JsonCodec json,@Value("${app.zone}") String zone) {this.s=s;this.json=json;this.zone=ZoneId.of(zone);}
    public List<Map<String,Object>> cabinets() {return s.jdbc().queryForList("SELECT * FROM cabinets ORDER BY id");}
    public List<Map<String,Object>> stocks() {
        List<Map<String,Object>> rows=s.jdbc().queryForList("SELECT st.*,p.name,p.price_cents,p.weight_grams,p.threshold_qty,p.enabled,c.name AS cabinet_name,c.active_session FROM stocks st JOIN products p ON p.id=st.product_id JOIN cabinets c ON c.id=st.cabinet_id ORDER BY st.cabinet_id,st.product_id");
        for(var r:rows) {
            String active=(String)r.get("active_session");
            int held=active==null?0:SimulatorService.held(s,active).getOrDefault((String)r.get("product_id"),0);
            r.put("held",held);
            int onHand=((Number)r.get("on_hand")).intValue();
            r.put("lowStock",Boolean.TRUE.equals(r.get("enabled")) && onHand<=((Number)r.get("threshold_qty")).intValue());
            // 非零说明人工确认与仿真物理状态不一致；仅管理台审计显示，不给融合器提供答案。
            r.put("discrepancy",((Number)r.get("supplied")).intValue()-onHand-((Number)r.get("sold")).intValue()-held);
        }
        return rows;
    }
    @Transactional
    public void saveProduct(ProductInput p) {
        if(!p.id().matches("[A-Za-z0-9_-]{1,40}")) throw new BusinessException("商品编号只允许字母、数字、下划线和短横线");
        if(s.jdbc().queryForObject("SELECT COUNT(*) FROM cabinets WHERE active_session IS NOT NULL",Integer.class)>0)
            throw new BusinessException("有购物会话未结束，请完成支付/取消后再修改商品资料");
        int updated=s.jdbc().update("UPDATE products SET name=?,price_cents=?,weight_grams=?,threshold_qty=?,enabled=? WHERE id=?",p.name(),p.priceCents(),p.weightGrams(),p.thresholdQty(),p.enabled(),p.id());
        if(updated==0) {
            s.jdbc().update("INSERT INTO products VALUES(?,?,?,?,?,?)",p.id(),p.name(),p.priceCents(),p.weightGrams(),p.thresholdQty(),p.enabled());
            for(var c:cabinets()) s.jdbc().update("INSERT INTO stocks(cabinet_id,product_id,on_hand,supplied) VALUES(?,?,0,0)",c.get("id"),p.id());
        }
    }
    @Transactional
    public void createCabinet(CabinetInput c) {
        s.jdbc().update("INSERT INTO cabinets(id,name,location) VALUES(?,?,?)",c.id(),c.name(),c.location());
        for(Product p:s.products()) s.jdbc().update("INSERT INTO stocks(cabinet_id,product_id,on_hand,supplied) VALUES(?,?,0,0)",c.id(),p.id());
    }
    @Transactional
    public void replenish(StockRequest r) {
        var c=s.one("SELECT * FROM cabinets WHERE id=? FOR UPDATE",r.cabinetId());
        if(c.get("active_session")!=null) throw new BusinessException("柜体有未结束会话，不能补货");
        s.product(r.productId());
        int n=s.jdbc().update("UPDATE stocks SET on_hand=on_hand+?,supplied=supplied+? WHERE cabinet_id=? AND product_id=?",r.quantity(),r.quantity(),r.cabinetId(),r.productId());
        if(n!=1) throw new BusinessException("柜体商品库存记录不存在");
        s.log(r.cabinetId(),r.productId(),"RESTOCK",r.quantity(),null);
    }
    public Map<String,Object> dashboard(String dateText) {
        LocalDate date;
        try{date=dateText==null?LocalDate.now(zone):LocalDate.parse(dateText);}
        catch(Exception e){throw new BusinessException("日期格式应为yyyy-MM-dd");}
        long start=date.atStartOfDay(zone).toInstant().toEpochMilli();
        long end=date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        var paid=s.jdbc().queryForList("SELECT * FROM orders WHERE state='PAID' AND paid_at>=? AND paid_at<? ORDER BY paid_at DESC",start,end);
        long revenue=0,quantity=0;
        Map<String,Map<String,Object>> perProduct=new LinkedHashMap<>();
        Map<String,Long> perCabinet=new LinkedHashMap<>();
        long[] hours=new long[24];
        for(var order:paid) {
            long amount=((Number)order.get("total_cents")).longValue();
            revenue+=amount;
            perCabinet.merge((String)order.get("cabinet_id"),amount,Long::sum);
            int hour=Instant.ofEpochMilli(((Number)order.get("paid_at")).longValue()).atZone(zone).getHour();
            hours[hour]+=amount;
            List<OrderItem> items=json.read((String)order.get("items_json"),new TypeReference<>(){});
            for(OrderItem item:items) {
                quantity+=item.quantity();
                var row=perProduct.computeIfAbsent(item.productId(),k->new LinkedHashMap<>(Map.of("productId",k,"name",item.name(),"quantity",0,"amountCents",0L)));
                row.put("quantity",((Number)row.get("quantity")).intValue()+item.quantity());
                row.put("amountCents",((Number)row.get("amountCents")).longValue()+item.subtotalCents());
            }
        }
        var inventory=stocks();
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("date",date.toString());result.put("revenueCents",revenue);result.put("paidOrders",paid.size());
        result.put("soldQuantity",quantity);result.put("productSales",perProduct.values());result.put("cabinetRevenue",perCabinet);
        result.put("hourlyRevenue",hours);result.put("stocks",inventory);
        result.put("lowStock",inventory.stream().filter(r->Boolean.TRUE.equals(r.get("lowStock"))).toList());
        result.put("pendingEvents",s.jdbc().queryForObject("SELECT COUNT(*) FROM perception_events e JOIN shopping_sessions ss ON ss.id=e.session_id WHERE e.status='PENDING' AND ss.state NOT IN ('PAID','CANCELLED')",Integer.class));
        return result;
    }
}
