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
    private final SimulatorService simulator; private final AdminService admin;
    public ApiController(DataStore s,AuthService auth,ShoppingService shopping,SimulatorService simulator,AdminService admin) {
        this.s=s;this.auth=auth;this.shopping=shopping;this.simulator=simulator;this.admin=admin;
    }
    @PostMapping("/auth/login") public Object login(@Valid @RequestBody Login r){return auth.login(r);}
    @GetMapping("/cabinets") public Object cabinets(){return admin.cabinets();}
    @GetMapping("/products") public Object products(){return s.products();}
    @PostMapping("/sessions") public Object open(@Valid @RequestBody OpenRequest r,@RequestAttribute Principal principal){return shopping.open(r.cabinetId(),principal);}
    @GetMapping("/sessions") public Object sessions(@RequestAttribute Principal principal){
        return s.jdbc().queryForList("SELECT * FROM shopping_sessions WHERE user_id=? ORDER BY created_at DESC",principal.userId());
    }
    @GetMapping("/sessions/{id}") public Object session(@PathVariable String id,@RequestAttribute Principal principal){return shopping.detail(id,principal);}
    @PostMapping("/sessions/{id}/close") public Object close(@PathVariable String id,@RequestAttribute Principal principal){return shopping.close(id,principal);}
    @PostMapping("/sessions/{id}/events/{event}/resolve") public Object resolve(@PathVariable String id,@PathVariable String event,@Valid @RequestBody ResolveRequest r,@RequestAttribute Principal principal){return shopping.resolve(id,event,r,principal);}
    @GetMapping("/orders") public Object orders(@RequestAttribute Principal principal){
        return s.jdbc().queryForList("SELECT id,session_id,cabinet_id,state,total_cents,created_at,paid_at FROM orders WHERE user_id=? ORDER BY created_at DESC",principal.userId());
    }
    @GetMapping("/orders/{id}") public Object order(@PathVariable String id,@RequestAttribute Principal principal){return shopping.order(id,principal);}
    @PostMapping("/orders/{id}/confirm") public Object confirm(@PathVariable String id,@RequestAttribute Principal principal){return shopping.confirm(id,principal);}
    @PostMapping("/orders/{id}/pay") public Object pay(@PathVariable String id,@Valid @RequestBody PaymentRequest r,@RequestAttribute Principal principal){return shopping.pay(id,r,principal);}
    @PostMapping("/orders/{id}/cancel") public Object cancel(@PathVariable String id,@RequestAttribute Principal principal){return shopping.cancel(id,principal);}
    @GetMapping("/admin/dashboard") public Object dashboard(@RequestParam(required=false) String date){return admin.dashboard(date);}
    @GetMapping("/admin/stocks") public Object stocks(){return admin.stocks();}
    @GetMapping("/admin/sessions") public Object allSessions(){return s.jdbc().queryForList("SELECT * FROM shopping_sessions ORDER BY created_at DESC LIMIT 200");}
    @GetMapping("/admin/orders") public Object allOrders(){return s.jdbc().queryForList("SELECT * FROM orders ORDER BY created_at DESC LIMIT 200");}
    @GetMapping("/admin/inventory-logs") public Object logs(){return s.jdbc().queryForList("SELECT * FROM inventory_logs ORDER BY created_at DESC LIMIT 200");}
    @PostMapping("/admin/products") public Object product(@Valid @RequestBody ProductInput r){admin.saveProduct(r);return Map.of("message","商品已保存");}
    @PostMapping("/admin/cabinets") public Object cabinet(@Valid @RequestBody CabinetInput r){admin.createCabinet(r);return Map.of("message","柜体已创建");}
    @PostMapping("/admin/restock") public Object restock(@Valid @RequestBody StockRequest r){admin.replenish(r);return Map.of("message","补货已入库");}
    @PostMapping("/admin/sessions/{id}/simulate") public Object simulate(@PathVariable String id,@Valid @RequestBody SimulateRequest r){return simulator.simulate(id,r);}
    @PostMapping("/admin/sessions/{id}/return-and-cancel") public Object returnAndCancel(@PathVariable String id){return simulator.returnAllAndCancel(id);}
}
