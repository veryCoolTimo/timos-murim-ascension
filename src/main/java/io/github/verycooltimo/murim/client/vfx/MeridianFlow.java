package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Меридианы: поток по телу СНИЗУ ВВЕРХ через узловые точки.
 *
 * <p>Это переделка по прямому замечанию автора. Прежняя версия вела прямые линии от
 * каждой конечности к средоточию: все они шли в одну сторону, тело оставалось пустым,
 * и это читалось как спицы колеса, а не как каналы, проложенные по телу.
 *
 * <p><b>Устройство.</b> Тело описано цепочкой узлов — ступни, колени, средоточие, грудь,
 * плечи, кисти, голова. Между соседними узлами идёт отрезок канала. Поток заполняет
 * цепочку от ступней вверх: у каждого отрезка своя доля общего пути, и он проявляется,
 * когда фронт потока до него доходит. Узел вспыхивает в момент прохождения фронта —
 * так на теле видны именно точки, а не сплошная линия.
 *
 * <p>Порядок ветвей задан руками, а не вычислен: снизу вверх поток идёт к средоточию,
 * оттуда к груди, и только затем расходится в руки и голову. Автоматическая раскладка
 * дала бы формально верный, но бессмысленный порядок.
 */
public final class MeridianFlow {

    /** Отрезок канала между двумя узлами с долей общего пути. */
    private record Link(Vec3 from, Vec3 to, float start, float end) {
    }

    /** Узел с долей пути, на которой он вспыхивает. */
    private record Node(Vec3 at, float at01) {
    }

    /**
     * Рисует поток и узлы.
     *
     * @param chain    точки тела в порядке снизу вверх; {@code null} внутри допустимы
     * @param progress фронт потока от 0 до 1
     * @param width    полуширина канала
     * @param alpha    непрозрачность канала
     */
    public static void draw(VertexConsumer channel, VertexConsumer nodes, PoseStack.Pose pose,
                            Vec3 cameraLocal, List<Vec3[]> chain, float progress,
                            double width, float alpha, VfxColour line, VfxColour node) {
        float front = Mth.clamp(progress, 0.0F, 1.0F);
        if (front <= 0.0F || alpha <= 0.0F) {
            return;
        }
        List<Link> links = new ArrayList<>();
        List<Node> points = new ArrayList<>();
        double total = 0.0D;
        for (Vec3[] pair : chain) {
            if (pair[0] != null && pair[1] != null) {
                total += pair[0].distanceTo(pair[1]);
            }
        }
        if (total <= 1.0E-4D) {
            return;
        }
        double walked = 0.0D;
        for (Vec3[] pair : chain) {
            if (pair[0] == null || pair[1] == null) {
                continue;
            }
            double length = pair[0].distanceTo(pair[1]);
            float start = (float) (walked / total);
            walked += length;
            float end = (float) (walked / total);
            links.add(new Link(pair[0], pair[1], start, end));
            points.add(new Node(pair[1], end));
        }

        for (Link link : links) {
            if (front <= link.start()) {
                continue;
            }
            // Доля отрезка, до которой дошёл фронт. Канал ПРОРАСТАЕТ, а не проявляется
            // целиком: иначе направление потока не читается.
            float filled = Mth.clamp((front - link.start()) / (link.end() - link.start()),
                                     0.0F, 1.0F);
            // Канал — ЦЕПОЧКА ТОЧЕК, а не сплошная лента.
            //
            // Так его и описывает автор: по телу идут кружки и точки, через них — линии.
            // Практическая причина та же: лента в этой сцене до экрана не доходила
            // (усиленная разница кадров показала ноль), а точки рисуются заведомо —
            // узлы на тех же кадрах видны. Менять непонятное на понятное дешевле, чем
            // разбираться в мешере ради формы, которая и не нужна.
            double span = link.from().distanceTo(link.to());
            int beads = Math.max(3, (int) Math.ceil(span / (width * 2.4D)));
            for (int i = 0; i <= beads; i++) {
                float t = (i / (float) beads) * filled;
                Vec3 at = link.from().add(link.to().subtract(link.from()).scale(t));
                // Хвост тусклее фронта: видно, куда поток идёт.
                float head = Mth.clamp(1.0F - (filled - t) * 3.0F, 0.30F, 1.0F);
                // Размер точки, а не полуширина ленты. Первая попытка отдавала сюда
                // ширину ленты как есть, и канал выходил по два-три пикселя — впятеро
                // тоньше узлов, то есть невидимым. Узлы на тех же кадрах были видны,
                // и это сразу указало на масштаб, а не на слой.
                // Два слоя на каждую точку: плотное ядро и мягкий ореол вокруг.
                // Одиночная точка теряется на тёмном теле — площадь маски давала
                // тридцать пикселей на всю фазу. Ореол связывает точки в линию.
                VfxDraw.billboard(channel, pose, at, cameraLocal, width * (3.2D + 1.8D * head),
                                  alpha * head * 0.35F, line.red(), line.green(), line.blue());
                VfxDraw.billboard(channel, pose, at, cameraLocal, width * (1.5D + 0.9D * head),
                                  alpha * head, line.red(), line.green(), line.blue());
            }
        }

        for (Node point : points) {
            if (front < point.at01()) {
                continue;
            }
            // Узел вспыхивает при прохождении фронта и оседает до ровного свечения:
            // именно вспышки делают точки на теле заметными.
            float since = front - point.at01();
            float flash = Mth.clamp(1.0F - since * 6.0F, 0.0F, 1.0F);
            double size = width * (2.2D + 3.4D * flash);
            VfxDraw.billboard(nodes, pose, point.at(), cameraLocal, size,
                              alpha * (0.55F + 0.45F * flash),
                              node.red(), node.green(), node.blue());
        }
    }

    private MeridianFlow() {
    }
}
