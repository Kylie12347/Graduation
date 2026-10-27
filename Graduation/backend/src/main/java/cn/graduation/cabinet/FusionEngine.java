package cn.graduation.cabinet;

import org.springframework.stereotype.Component;
import java.util.*;
import static cn.graduation.cabinet.Models.*;

/**
 * 规则融合引擎是纯函数：只接收观测Signal和商品标称资料，没有数据库或仿真操作参数。
 * 三步按顺序运行：重量产生候选，RFID交叉验证，视觉检查“是否存在差异”。
 * 第一版每次只模拟一种商品的1~10件；组合商品分多次操作，避免组合搜索爆炸。
 */
@Component
public class FusionEngine {
    public FusionResult analyze(Signal signal, List<Product> catalog) {
        List<String> steps = new ArrayList<>();
        List<Candidate> candidates = new ArrayList<>();
        int absolute = Math.abs(signal.weightDeltaGrams());
        String direction = signal.weightDeltaGrams() < 0 ? "TAKE" : "RETURN";
        int sign = "TAKE".equals(direction) ? -1 : 1;
        // 容差取绝对20克与理论重量3%的较大值，数量不同也分别产生候选。
        for (Product p : catalog) {
            for (int n = 1; n <= 10; n++) {
                int expected = p.weightGrams() * n;
                int residual = Math.abs(absolute - expected);
                if (absolute >= 5 && residual <= Math.max(20, expected * 0.03))
                    candidates.add(new Candidate(p.id(), p.name(), n, residual));
            }
        }
        steps.add("① 重量：变化" + signal.weightDeltaGrams() + "克，得到" + candidates.size() + "个候选（20克/3%容差）");
        List<Candidate> narrowed = new ArrayList<>(candidates);
        boolean rfidPresent = !signal.rfidDelta().isEmpty();
        if (rfidPresent) {
            narrowed.removeIf(c -> signal.rfidDelta().size() != 1 ||
                !Objects.equals(signal.rfidDelta().get(c.productId()), sign * c.quantity()));
            steps.add("② RFID：观测标签数量变化" + signal.rfidDelta() + "，交叉验证后剩" + narrowed.size() + "个候选");
        } else {
            steps.add("② RFID：本窗口未读到有效变化（可模拟漏读），保留重量候选");
        }
        steps.add("③ 视觉：" + (signal.visionDifferent() ? "检测到差异，与前述判断不一致" : "未发现差异，辅助校验通过"));
        String reason;
        if (signal.weightDeltaGrams() == 0 || candidates.isEmpty()) reason = "重量不足或未匹配商品，请人工确认";
        else if (signal.visionDifferent()) reason = "视觉差异报警，请人工确认";
        else if (rfidPresent && narrowed.isEmpty()) reason = "RFID与重量冲突，请人工确认";
        else if (narrowed.size() != 1) reason = "存在多个商品/数量候选，请人工确认";
        else {
            Candidate selected = narrowed.get(0);
            return new FusionResult(direction, "CONFIRMED", selected.productId(), selected.quantity(),
                candidates, steps, rfidPresent ? "重量、RFID一致，视觉无差异" : "重量候选唯一，视觉无差异；RFID缺失已记录");
        }
        return new FusionResult(direction, "PENDING", null, 0, candidates, steps, reason);
    }
}
