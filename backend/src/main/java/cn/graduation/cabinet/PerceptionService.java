package cn.graduation.cabinet;

import java.util.*;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static cn.graduation.cabinet.Models.*;
import static cn.graduation.cabinet.DataStore.*;
import static cn.graduation.cabinet.PerceptionModels.*;

/** 小程序负责模拟读数；服务端校验柜体归属、比较观测、审核和结算。 */
@Service
class PerceptionService {
    private final DataStore s; private final JsonCodec json; private final RfidService rfid; private final ShoppingService shopping;
    PerceptionService(DataStore s,JsonCodec json,RfidService rfid,ShoppingService shopping){
        this.s=s;this.json=json;this.rfid=rfid;this.shopping=shopping;
    }
    @Transactional
    public Map<String,Object> open(String cabinet,Principal user){
        // 按消费者锁定：同一微信端并发扫两台柜也只允许创建一个活跃会话。
        s.jdbc().queryForList("SELECT token_hash FROM consumer_sessions WHERE user_id=? FOR UPDATE",user.userId());
        // 同一柜重复扫码复用原会话，不能覆盖快照或新建订单。
        var c=s.one("SELECT * FROM cabinets WHERE id=? FOR UPDATE",cabinet);
        if(c.get("active_session") instanceof String current){
            var existing=s.one("SELECT user_id FROM shopping_sessions WHERE id=?",current);
            if(user.userId().equals(existing.get("user_id"))) return shopping.detail(current,user);
        }
        var opened=shopping.open(cabinet,user);
        rfid.ensure(cabinet);
        var catalog=catalog(cabinet);
        long grams=catalog.stream().mapToLong(p->number(p.get("on_hand"))*(long)number(p.get("weight_grams"))).sum();
        var before=Map.of("grams",grams,"tags",rfid.scan(cabinet),"tagProducts",rfid.bindings(cabinet),"catalog",catalog);
        s.jdbc().update("INSERT INTO cabinet_audits(session_id,before_json) VALUES(?,?)",opened.get("id"),json.write(before));
        return shopping.detail((String)opened.get("id"),user);
    }
    @Transactional
    public Map<String,Object> abandon(String id,Principal user){
        shopping.checkOwner(id,user);
        var session=s.lockSession(id);
        var audit=auditRow(id);
        if("CANCELLED".equals(session.get("state"))) return shopping.detail(id,user);
        if(!"OPEN".equals(session.get("state"))||audit.get("after_json")!=null)
            throw new BusinessException("已提交的会话不能放弃，请联系运营人员");
        var closed=shopping.close(id,user);
        s.jdbc().update("UPDATE cabinet_audits SET decision_note=? WHERE session_id=?","未提交仿真观测，结束空柜会话",id);
        shopping.finishCancel(s.lockSession(id),(String)closed.get("order_id"));
        return shopping.detail(id,user);
    }
    List<Map<String,Object>> catalog(String cabinet){
        return s.jdbc().queryForList("SELECT p.id,p.name,p.weight_grams,st.on_hand,pp.image_url,COALESCE(pp.weight_priority,FALSE) AS weight_priority FROM stocks st JOIN products p ON p.id=st.product_id LEFT JOIN product_profiles pp ON pp.product_id=p.id WHERE st.cabinet_id=? AND p.enabled=TRUE AND st.on_hand>0 ORDER BY p.id",cabinet);
    }
    Map<String,Object> read(Object raw){return json.read((String)raw,new TypeReference<>(){});}
    Map<String,Object> auditRow(String id){return s.one("SELECT * FROM cabinet_audits WHERE session_id=?",id);}
    Map<String,Object> baseline(String id,Principal user){
        var session=shopping.checkOwner(id,user);
        if(!"OPEN".equals(session.get("state"))) throw new BusinessException("柜门已关，无法修改仿真数据");
        return read(auditRow(id).get("before_json"));
    }
    @SuppressWarnings("unchecked")
    List<Map<String,Object>> goods(Map<String,Object> before){return (List<Map<String,Object>>)before.get("catalog");}
    List<Photo> validate(Report report,Map<String,Object> before){
        var valid=(List<?>)before.get("tags");
        var first=new HashSet<>(report.beforeTags()); var last=new HashSet<>(report.afterTags());
        if(first.size()!=report.beforeTags().size()||last.size()!=report.afterTags().size()||!valid.containsAll(first)||!valid.containsAll(last))
            throw new BusinessException("读数存在重复或非本次柜体的 RFID 标签");
        var frames=report.photos().stream().sorted(Comparator.comparingLong(Photo::timestamp)).toList();
        if(frames.stream().map(Photo::timestamp).distinct().count()!=frames.size()) throw new BusinessException("照片时间戳不能重复");
        for(var f:frames){
            if(f.timestamp()>now()+300000 || !f.dataUrl().matches("(?s)data:image/(jpeg|png);base64,[A-Za-z0-9+/]+={0,2}"))
                throw new BusinessException("照片必须为有效 JPEG 或 PNG，拍摄时间不能晚于当前时间");
            var parts=f.dataUrl().split(",",2);
            try {
                byte[] b=Base64.getDecoder().decode(parts[1]);
                boolean png=b.length>=8 && b[0]==(byte)137 && b[1]==80 && b[2]==78 && b[3]==71;
                boolean jpg=b.length>=3 && b[0]==(byte)255 && b[1]==(byte)216 && b[2]==(byte)255;
                if(parts[0].contains("png")?!png:!jpg) throw new IllegalArgumentException();
            } catch(IllegalArgumentException e){throw new BusinessException("照片数据无效，请重新上传");}
        }
        return frames;
    }
    @Transactional
    public Map<String,Object> submit(String id,Principal user,Report report,List<Photo> photos,VisionResult vision){
        shopping.checkOwner(id,user);
        var session=s.lockSession(id);
        if(!"OPEN".equals(session.get("state"))) return shopping.detail(id,user);
        var before=read(auditRow(id).get("before_json"));
        var goods=goods(before);
        var skuByName=new HashMap<String,String>(); var ambiguous=new HashSet<String>();
        for(var p:goods) if(skuByName.put((String)p.get("name"),(String)p.get("id"))!=null) ambiguous.add((String)p.get("name"));
        var vis=new TreeMap<String,Integer>();
        boolean reliable="OK".equals(vision.status()) || goods.isEmpty();
        for(var item:vision.items()){
            String sku=skuByName.get(item.name());
            if(sku==null||ambiguous.contains(item.name())) reliable=false;
            else vis.put(sku,item.quantity());
        }
        var tagProducts=(Map<?,?>)before.get("tagProducts");
        var last=new HashSet<>(report.afterTags());
        var tagCounts=new TreeMap<String,Integer>();
        var missing=report.beforeTags().stream().filter(t->!last.contains(t)).sorted().toList();
        var initial=new HashSet<>(report.beforeTags());
        var added=report.afterTags().stream().filter(t->!initial.contains(t)).sorted().toList();
        for(var tag:missing) tagCounts.merge((String)tagProducts.get(tag),1,Integer::sum);
        int loss=report.beforeGrams()-report.afterGrams();
        var candidates=weightCandidates(loss,goods);
        Map<String,Integer> chosen=null;
        String route="DIRECT";
        String reason="RFID 与视觉的商品或数量不一致，或视觉无法确认";
        if(candidates.size()==1 && tagCounts.size()<=1 && vis.size()<=1 && Boolean.TRUE.equals(candidates.get(0).get("weight_priority"))){
            var p=candidates.get(0);
            chosen=Map.of((String)p.get("id"),number(p.get("quantity")));
            route="WEIGHT_PRIORITY";reason="特殊商品仅有一个重量候选，按重量优先结算";
        } else if(reliable && added.isEmpty() && tagCounts.equals(vis) && (!vis.isEmpty()||Math.abs(loss)<=20) && fits(vis,goods)){
            chosen=vis;reason="RFID 与视觉在商品及数量上完全一致";
        } else if(!added.isEmpty()) reason="关柜扫描出现开柜时未读到的标签，请核对";
        var after=new LinkedHashMap<String,Object>();
        after.put("beforeGrams",report.beforeGrams());after.put("afterGrams",report.afterGrams());
        after.put("beforeTags",report.beforeTags());after.put("afterTags",report.afterTags());
        after.put("rfidItems",tagCounts);after.put("missingTags",missing);after.put("addedTags",added);
        after.put("vision",vision);after.put("photos",photos);after.put("weightCandidates",candidates);
        s.jdbc().update("UPDATE cabinet_audits SET after_json=?,decision_note=? WHERE session_id=?",json.write(after),reason,id);
        var closed=shopping.close(id,user);
        if(chosen!=null) finalizeOrder(id,(String)closed.get("order_id"),(String)session.get("cabinet_id"),chosen,missing,route,reason);
        return shopping.detail(id,user);
    }
    static int number(Object o){return ((Number)o).intValue();}
    static List<Map<String,Object>> weightCandidates(int loss,List<Map<String,Object>> goods){
        List<Map<String,Object>> candidates=new ArrayList<>();
        if(loss<=20) return candidates;
        for(var p:goods){
            int weight=number(p.get("weight_grams"));
            for(int q=1;q<=Math.min(1000,number(p.get("on_hand")));q++){
                long expected=(long)q*weight;
                if(Math.abs(loss-expected)<=Math.max(20,expected*0.03))
                    candidates.add(Map.of("id",p.get("id"),"name",p.get("name"),"quantity",q,"weight_priority",p.get("weight_priority")));
            }
        }
        return candidates;
    }
    static boolean fits(Map<String,Integer> chosen,List<Map<String,Object>> goods){
        return chosen.entrySet().stream().allMatch(e->goods.stream().anyMatch(p->e.getKey().equals(p.get("id"))&&number(p.get("on_hand"))>=e.getValue()));
    }
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String,Object> review(String id,Review review,Principal user){
        if(!user.admin()) throw new BusinessException("仅运营人员可审核");
        var session=s.lockSession(id);
        var audit=auditRow(id);
        if(!"REVIEW".equals(session.get("state"))||audit.get("after_json")==null||audit.get("checkout_route")!=null)
            throw new BusinessException("当前会话无需审核");
        var chosen=new LinkedHashMap<String,Integer>();
        for(var line:review.items()) if(chosen.put(line.productId(),line.quantity())!=null) throw new BusinessException("请勿重复选择同一商品");
        if(!fits(chosen,goods(read(audit.get("before_json"))))) throw new BusinessException("审核商品超出开柜时本柜库存");
        var after=read(audit.get("after_json"));
        finalizeOrder(id,(String)session.get("order_id"),(String)session.get("cabinet_id"),chosen,(List<String>)after.get("missingTags"),"ADMIN_REVIEW",review.note());
        return shopping.detail(id,user);
    }
    private void finalizeOrder(String id,String order,String cabinet,Map<String,Integer> chosen,List<String> preferred,String route,String note){
        s.one("SELECT * FROM cabinets WHERE id=? FOR UPDATE",cabinet);
        s.jdbc().update("UPDATE cabinet_audits SET checkout_route=?,decision_note=?,reviewed_at=? WHERE session_id=?",route,note,now(),id);
        if(chosen.isEmpty()){
            shopping.finishCancel(s.lockSession(id),order);
            return;
        }
        List<OrderItem> lines=new ArrayList<>();
        for(var e:chosen.entrySet()){
            String sku=e.getKey(); int quantity=e.getValue(); Product p=s.product(sku);
            if(s.jdbc().update("UPDATE stocks SET on_hand=on_hand-? WHERE cabinet_id=? AND product_id=? AND on_hand>=?",quantity,cabinet,sku,quantity)!=1)
                throw new BusinessException("审核商品超出当前库存");
            rfid.reserve(cabinet,id,sku,quantity,preferred);
            s.log(cabinet,sku,"TAKE",quantity,id);
            lines.add(new OrderItem(sku,p.name(),quantity,p.priceCents(),Math.multiplyExact(quantity,p.priceCents()),p.imageUrl()));
        }
        int total=lines.stream().mapToInt(OrderItem::subtotalCents).reduce(0,Math::addExact);
        s.jdbc().update("UPDATE orders SET state='UNPAID',items_json=?,total_cents=? WHERE id=?",json.write(lines),total,order);
        s.jdbc().update("UPDATE shopping_sessions SET state='UNPAID' WHERE id=?",id);
    }
    @Transactional
    public Map<String,Object> cancelUnpaid(String id,CancelReview request,Principal user){
        if(!user.admin()) throw new BusinessException("仅运营人员可确认退回");
        var session=s.lockSession(id);
        var audit=auditRow(id);
        if("CANCELLED".equals(session.get("state"))) return shopping.detail(id,user);
        if(!"UNPAID".equals(session.get("state"))||audit.get("checkout_route")==null)
            throw new BusinessException("仅可在未支付且商品已归还时取消");
        String cabinet=(String)session.get("cabinet_id"), order=(String)session.get("order_id");
        s.one("SELECT * FROM cabinets WHERE id=? FOR UPDATE",cabinet);
        for(var row:s.jdbc().queryForList("SELECT product_id,COUNT(*) AS quantity FROM rfid_items WHERE cabinet_id=? AND held_session=? AND location='OUTSIDE' GROUP BY product_id",cabinet,id)){
            String sku=(String)row.get("product_id");int quantity=number(row.get("quantity"));
            s.jdbc().update("UPDATE stocks SET on_hand=on_hand+? WHERE cabinet_id=? AND product_id=?",quantity,cabinet,sku);
            s.log(cabinet,sku,"RETURN",quantity,id);
        }
        s.jdbc().update("UPDATE rfid_items SET location='IN_CABINET',held_session=NULL WHERE cabinet_id=? AND held_session=? AND location='OUTSIDE'",cabinet,id);
        s.jdbc().update("UPDATE cabinet_audits SET decision_note=?,reviewed_at=? WHERE session_id=?",request.note(),now(),id);
        shopping.finishCancel(session,order);
        return shopping.detail(id,user);
    }
    Map<String,Object> audit(String id,Principal user){
        var out=new LinkedHashMap<>(shopping.detail(id,user));
        var rows=s.jdbc().queryForList("SELECT * FROM cabinet_audits WHERE session_id=?",id);
        if(!rows.isEmpty()){
            var row=rows.get(0);
            out.put("before",read(row.get("before_json")));
            if(row.get("after_json")!=null) out.put("after",read(row.get("after_json")));
            out.put("decisionNote",row.get("decision_note"));
        }
        return out;
    }
}
