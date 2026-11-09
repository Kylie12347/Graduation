package cn.graduation.cabinet;

import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import java.util.*;
import static cn.graduation.cabinet.Models.*;

/** REST控制器只接收/校验参数；复杂逻辑放在Service，方便单独测试。 */
@RestController
@RequestMapping("/api")
public class ApiController {
    private final DataStore s; private final AuthService auth; private final ShoppingService shopping;
    private final RfidService rfid;
    private final StorefrontService storefront;
    private final PerceptionService perception; private final VisionService vision; private final SimulatorService simulator; private final AdminService admin;
    public ApiController(DataStore s,AuthService auth,ShoppingService shopping,SimulatorService simulator,AdminService admin,RfidService rfid,StorefrontService storefront,PerceptionService perception,VisionService vision) {
        this.s=s;this.auth=auth;this.shopping=shopping;this.simulator=simulator;this.admin=admin;this.rfid=rfid;this.storefront=storefront;this.perception=perception;this.vision=vision;
    }
    @PostMapping("/auth/login") public Object login(@Valid @RequestBody Login r){return auth.login(r);}
    @GetMapping("/cabinets") public Object cabinets(){return admin.cabinets();}
    @GetMapping("/cabinets/{id}/catalog") public Object catalog(@PathVariable String id){return storefront.catalog(id);}
    @GetMapping("/products") public Object products(){return s.products();}
    @PostMapping("/sessions") public Object open(@Valid @RequestBody OpenRequest r,@RequestAttribute Principal principal){if(principal.admin()) throw new BusinessException("请用小程序扫码开启柜体");return perception.open(r.cabinetId(),principal);}
    @GetMapping("/sessions") public Object sessions(@RequestAttribute Principal principal){
        return s.jdbc().queryForList("SELECT * FROM shopping_sessions WHERE user_id=? ORDER BY created_at DESC",principal.userId());
    }
    @GetMapping("/sessions/{id}") public Object session(@PathVariable String id,@RequestAttribute Principal principal){return shopping.detail(id,principal);}
    @PostMapping("/sessions/{id}/close") public Object close(@PathVariable String id,@RequestAttribute Principal principal){if(!principal.admin()) throw new BusinessException("请从小程序仿真页提交读数并关柜"); rejectNewFlow(id);return shopping.close(id,principal);}
    @PostMapping("/sessions/{id}/events/{event}/resolve") public Object resolve(@PathVariable String id,@PathVariable String event,@Valid @RequestBody ResolveRequest r,@RequestAttribute Principal principal){if(!principal.admin()) throw new BusinessException("请等待运营人员核对");rejectNewFlow(id);return shopping.resolve(id,event,r,principal);}
    @PostMapping("/sessions/{id}/abandon") public Object abandon(@PathVariable String id,@RequestAttribute Principal principal){return perception.abandon(id,principal);}
    @GetMapping("/sessions/{id}/simulation") public Object baseline(@PathVariable String id,@RequestAttribute Principal principal){return perception.baseline(id,principal);}
    @PostMapping("/sessions/{id}/perception") public Object perception(@PathVariable String id,@Valid @RequestBody PerceptionModels.Report report,@RequestAttribute Principal principal){
        var session=shopping.checkOwner(id,principal);
        if(!"OPEN".equals(session.get("state"))) return shopping.detail(id,principal);
        var before=perception.baseline(id,principal);
        var photos=perception.validate(report,before);
        var names=perception.goods(before).stream().map(p->(String)p.get("name")).distinct().toList();
        var detected=names.isEmpty()?new PerceptionModels.VisionResult("NO_CATALOG",List.of()):vision.identify(photos,names);
        return perception.submit(id,principal,report,photos,detected);
    }
    @GetMapping("/admin/sessions/{id}/audit") public Object audit(@PathVariable String id,@RequestAttribute Principal principal){return perception.audit(id,principal);}
    @PostMapping("/admin/sessions/{id}/review") public Object review(@PathVariable String id,@Valid @RequestBody PerceptionModels.Review request,@RequestAttribute Principal principal){return perception.review(id,request,principal);}
    @PostMapping("/admin/sessions/{id}/cancel-unpaid") public Object cancelUnpaid(@PathVariable String id,@Valid @RequestBody PerceptionModels.CancelReview request,@RequestAttribute Principal principal){return perception.cancelUnpaid(id,request,principal);}
    private void rejectNewFlow(String id){
        if(s.jdbc().queryForObject("SELECT COUNT(*) FROM cabinet_audits WHERE session_id=?",Integer.class,id)>0)
            throw new BusinessException("新版会话必须使用小程序仿真和开关柜审核接口");
    }
    @GetMapping("/orders") public Object orders(@RequestAttribute Principal principal){
        return s.jdbc().queryForList("SELECT id,session_id,cabinet_id,state,total_cents,created_at,paid_at FROM orders WHERE user_id=? ORDER BY created_at DESC",principal.userId());
    }
    @GetMapping("/orders/{id}") public Object order(@PathVariable String id,@RequestAttribute Principal principal){return shopping.order(id,principal);}
    @PostMapping("/orders/{id}/confirm") public Object confirm(@PathVariable String id,@RequestAttribute Principal principal){if(!principal.admin()) throw new BusinessException("请等待运营人员核对");rejectNewFlow((String)shopping.order(id,principal).get("session_id"));return shopping.confirm(id,principal);}
    @PostMapping("/orders/{id}/pay") public Object pay(@PathVariable String id,@Valid @RequestBody PaymentRequest r,@RequestAttribute Principal principal){if(principal.admin()) throw new BusinessException("请由消费者付款");return shopping.pay(id,r,principal);}
    @PostMapping("/orders/{id}/cancel") public Object cancel(@PathVariable String id,@RequestAttribute Principal principal){return shopping.cancel(id,principal);}
    @GetMapping("/admin/dashboard") public Object dashboard(@RequestParam(required=false) String date,@RequestParam(required=false) String cabinetId){return admin.dashboard(date,cabinetId);}
    @GetMapping("/admin/rfid-items") public Object rfidItems(@RequestParam String cabinetId){return rfid.items(cabinetId);}
    @GetMapping("/admin/stocks") public Object stocks(){return admin.stocks();}
    @GetMapping("/admin/sessions") public Object allSessions(){return s.jdbc().queryForList("SELECT ss.*,c.name AS cabinet_name FROM shopping_sessions ss JOIN cabinets c ON c.id=ss.cabinet_id ORDER BY CASE WHEN ss.state='REVIEW' THEN 0 ELSE 1 END,ss.created_at DESC LIMIT 200");}
    @GetMapping("/admin/orders") public Object allOrders(@RequestParam(required=false) String cabinetId,@RequestParam(required=false) String state,@RequestParam(required=false) String route){
        String sql="SELECT o.id,o.session_id,o.cabinet_id,o.state,o.total_cents,o.created_at,o.paid_at,CASE WHEN a.session_id IS NULL THEN 'LEGACY' ELSE COALESCE(a.checkout_route,'PENDING') END AS checkout_route FROM orders o LEFT JOIN cabinet_audits a ON a.session_id=o.session_id WHERE 1=1";
        List<Object> args=new ArrayList<>();
        if(cabinetId!=null&&!cabinetId.isBlank()){sql+=" AND o.cabinet_id=?";args.add(cabinetId);}
        if(state!=null&&!state.isBlank()){sql+=" AND o.state=?";args.add(state);}
        if(route!=null&&!route.isBlank()){sql+=" AND (CASE WHEN a.session_id IS NULL THEN 'LEGACY' ELSE COALESCE(a.checkout_route,'PENDING') END)=?";args.add(route);}
        return s.jdbc().queryForList(sql+" ORDER BY o.created_at DESC LIMIT 200",args.toArray());
    }
    @GetMapping("/admin/inventory-logs") public Object logs(){return s.jdbc().queryForList("SELECT * FROM inventory_logs ORDER BY created_at DESC LIMIT 200");}
    @PostMapping("/admin/products") public Object product(@Valid @RequestBody ProductInput r){admin.saveProduct(r);return Map.of("message","商品已保存");}
    @PostMapping("/admin/cabinets") public Object cabinet(@Valid @RequestBody CabinetInput r){admin.createCabinet(r);return Map.of("message","柜体已创建");}
    @PostMapping("/admin/restock") public Object restock(@Valid @RequestBody StockRequest r){admin.replenish(r);return Map.of("message","补货已入库");}
    @PostMapping("/admin/sessions/{id}/return-and-cancel") public Object returnAndCancel(@PathVariable String id){rejectNewFlow(id);return simulator.returnAllAndCancel(id);}
}
