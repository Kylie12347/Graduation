package cn.graduation.cabinet;

import org.junit.jupiter.api.Test;
import java.util.*;
import static cn.graduation.cabinet.Models.*;
import static org.assertj.core.api.Assertions.*;

/** 不依赖Spring或数据库，验证规则在相近重量/漏读/冲突下的实际行为。 */
class FusionEngineTest {
    final FusionEngine engine=new FusionEngine();
    final List<Product> catalog=List.of(new Product("COLA","可乐",350,330,3,true),
        new Product("JUICE","橙汁",500,330,3,true),new Product("WATER","水",200,550,3,true));
    @Test void sameWeightNeedsRfidToDisambiguate() {
        var result=engine.analyze(new Signal(-331,Map.of("COLA",-1),false),catalog);
        assertThat(result.status()).isEqualTo("CONFIRMED");
        assertThat(result.productId()).isEqualTo("COLA");
        assertThat(result.candidates()).hasSize(2);
        assertThat(result.steps()).hasSize(3);
    }
    @Test void missedRfidDoesNotGuessBetweenIdenticalWeights() {
        assertThat(engine.analyze(new Signal(-330,Map.of(),false),catalog).status()).isEqualTo("PENDING");
    }
    @Test void uniqueWeightAndNoVisualDifferenceCanSurviveRfidMiss() {
        assertThat(engine.analyze(new Signal(-548,Map.of(),false),catalog).productId()).isEqualTo("WATER");
    }
    @Test void visualDifferenceRequiresConfirmationEvenWithMatchingRfid() {
        assertThat(engine.analyze(new Signal(-330,Map.of("COLA",-1),true),catalog).status()).isEqualTo("PENDING");
    }
    @Test void inconsistentRfidIsNotAllowedToOverrideWeight() {
        assertThat(engine.analyze(new Signal(-550,Map.of("COLA",-1),false),catalog).status()).isEqualTo("PENDING");
    }
    @Test void returnTwoItemsHasPositiveSign() {
        var result=engine.analyze(new Signal(1100,Map.of("WATER",2),false),catalog);
        assertThat(result.direction()).isEqualTo("RETURN");
        assertThat(result.quantity()).isEqualTo(2);
    }
    @Test void zeroWeightAndHugeErrorStayPending() {
        assertThat(engine.analyze(new Signal(0,Map.of("COLA",-1),false),catalog).status()).isEqualTo("PENDING");
        assertThat(engine.analyze(new Signal(-400,Map.of("COLA",-1),false),catalog).status()).isEqualTo("PENDING");
    }
}
