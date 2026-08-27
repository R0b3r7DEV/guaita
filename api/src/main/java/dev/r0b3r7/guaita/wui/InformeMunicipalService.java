package dev.r0b3r7.guaita.wui;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfTemplate;
import com.lowagie.text.pdf.PdfWriter;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Informe municipal de interfaz urbano-forestal en PDF (docs/05). PRIVACIDAD (T2): solo referencia
 * catastral y coordenada, nunca titularidad. Los TRES avisos (descargo, limitación del MDT25 y
 * error de la geometría catastral) van en la PRIMERA página, no en un anexo. La advertencia de
 * margen se separa VISUALMENTE de los incumplimientos: una edificación a 32 m en llano CUMPLE
 * legalmente.
 */
@Service
public class InformeMunicipalService {

  private static final Color ROJO = new Color(0xE0, 0x2B, 0x1F); // crítico
  private static final Color AMBAR = new Color(0xF0, 0x86, 0x1B); // incumple
  private static final Color VERDE = new Color(0x2E, 0x7D, 0x32); // cumple
  private static final Color GRIS = new Color(0x60, 0x60, 0x60); // cautela técnica
  private static final Color TINTA = new Color(0x22, 0x22, 0x22);

  private static final String DESCARGO =
      "Estimación geométrica automatizada a partir de cartografía oficial (Catastro INSPIRE, PATFOR"
          + " y MDT25). NO constituye una certificación de cumplimiento normativo, que corresponde"
          + " al órgano competente previa inspección.";
  private static final String AVISO_MDT =
      "La pendiente se obtiene del modelo digital del terreno MDT25 (25 m de resolución), que suaviza"
          + " el relieve: la pendiente calculada es SISTEMÁTICAMENTE MENOR que la real. Algunas"
          + " edificaciones clasificadas con franja de 30 m podrían estar en pendiente > 30 % y"
          + " requerir 50 m. El informe SUBESTIMA el incumplimiento, no lo sobreestima.";
  private static final String AVISO_CATASTRO =
      "El análisis parte de la geometría del Catastro, con su propio error posicional. Por eso una"
          + " edificación que CUMPLE pero queda cerca del límite se marca como «cautela técnica»: no"
          + " es un incumplimiento, es un aviso para revisar en campo.";
  private static final String ANEXO_XI =
      "Anexo XI del TRLOTUP (Decreto Legislativo 1/2021), punto 1: «se deberá asegurar una faja"
          + " perimetral de protección mínima de 30 metros de ancho, medida desde el límite exterior"
          + " de la edificación […]. Dicha distancia se ampliará en función de la pendiente del"
          + " terreno, alcanzando, como mínimo, los 50 metros cuando la pendiente sea superior al"
          + " 30 %».";
  private static final String ART_145 =
      "Decreto 91/2023 (Reglamento forestal), art. 145: las edificaciones en interfaz urbano-forestal"
          + " «deberán cumplir con las normas establecidas en la normativa sectorial de ordenación"
          + " del territorio, urbanismo y paisaje» — es decir, REMITE al Anexo XI, sin fijar anchura"
          + " propia.";

  private final JdbcTemplate jdbc;

  public InformeMunicipalService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Genera el PDF del término. */
  public byte[] generar(String ineCode) {
    String nombre =
        jdbc.queryForObject(
            "select nombre from municipio where ine_code = ?", String.class, ineCode);
    Map<String, Object> ag =
        jdbc.queryForMap(
            "select count(*) total,"
                + " count(*) filter (where clase = 'critico') critico,"
                + " count(*) filter (where clase = 'incumple') incumple,"
                + " count(*) filter (where clase = 'cumple') cumple,"
                + " count(*) filter (where advertencia_margen) adv,"
                + " count(*) filter (where franja_m = 50) f50,"
                + " max(version_analisis) version"
                + " from wui_edificacion where ine_code = ?",
            ineCode);
    List<Map<String, Object>> edifs =
        jdbc.queryForList(
            "select e.ref_catastral ref,"
                + " round(st_y(st_transform(st_pointonsurface(e.geom), 4326))::numeric, 6) lat,"
                + " round(st_x(st_transform(st_pointonsurface(e.geom), 4326))::numeric, 6) lon,"
                + " w.clase, w.dist_forestal_m dist, w.franja_m franja, w.advertencia_margen adv,"
                + " round(e.pendiente_pct) pend"
                + " from wui_edificacion w join edificacion e on e.ref_catastral = w.ref_catastral"
                + " where w.ine_code = ? order by w.dist_forestal_m asc nulls last",
            ineCode);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Document doc = new Document(PageSize.A4, 42, 42, 42, 48);
    PdfWriter writer = PdfWriter.getInstance(doc, out);
    doc.open();

    portada(doc, nombre, ineCode, ag);
    avisos(doc);
    normativa(doc);
    resumen(doc, ag);
    doc.add(mapa(writer, ineCode));
    tabla(doc, edifs);

    doc.close();
    return out.toByteArray();
  }

  private void portada(Document doc, String nombre, String ine, Map<String, Object> ag) {
    Paragraph t = new Paragraph("Interfaz urbano-forestal — " + nombre, font(17, Font.BOLD, TINTA));
    doc.add(t);
    doc.add(
        new Paragraph(
            "Franja perimetral de protección legal · término " + ine, font(10, Font.NORMAL, GRIS)));
    doc.add(
        new Paragraph(
            "Versión del análisis: "
                + str(ag.get("version"))
                + " · cartografía: Catastro INSPIRE +"
                + " PATFOR + MDT25 · informe generado el "
                + LocalDate.now(),
            font(9, Font.NORMAL, GRIS)));
    doc.add(espacio(8));
  }

  private void avisos(Document doc) {
    PdfPTable caja = new PdfPTable(1);
    caja.setWidthPercentage(100);
    caja.addCell(cabecera("Avisos importantes (leer antes que los datos)", TINTA));
    caja.addCell(aviso("1. " + DESCARGO));
    caja.addCell(aviso("2. Limitación del MDT25. " + AVISO_MDT));
    caja.addCell(aviso("3. Geometría catastral. " + AVISO_CATASTRO));
    doc.add(caja);
    doc.add(espacio(8));
  }

  private void normativa(Document doc) {
    doc.add(new Paragraph("Base normativa", font(11, Font.BOLD, TINTA)));
    doc.add(new Paragraph(ANEXO_XI, font(8.5f, Font.ITALIC, TINTA)));
    doc.add(espacio(3));
    doc.add(new Paragraph(ART_145, font(8.5f, Font.ITALIC, TINTA)));
    doc.add(espacio(8));
  }

  private void resumen(Document doc, Map<String, Object> ag) {
    long total = num(ag.get("total"));
    long crit = num(ag.get("critico"));
    long inc = num(ag.get("incumple"));
    long cum = num(ag.get("cumple"));
    long adv = num(ag.get("adv"));
    long f50 = num(ag.get("f50"));

    doc.add(new Paragraph("Resumen (" + total + " edificaciones)", font(11, Font.BOLD, TINTA)));
    PdfPTable t = new PdfPTable(new float[] {3, 1});
    t.setWidthPercentage(100);
    t.addCell(celdaClase("INCUMPLIMIENTO — Crítico (dentro del monte)", ROJO));
    t.addCell(celdaNum(crit, ROJO));
    t.addCell(celdaClase("INCUMPLIMIENTO — Incumple (< franja)", AMBAR));
    t.addCell(celdaNum(inc, AMBAR));
    t.addCell(celdaClase("Cumple (>= franja) — cumple legalmente", VERDE));
    t.addCell(celdaNum(cum, VERDE));
    doc.add(t);
    doc.add(espacio(4));

    // La cautela técnica, SEPARADA VISUALMENTE (caja gris aparte): NO es un incumplimiento.
    PdfPTable c = new PdfPTable(1);
    c.setWidthPercentage(100);
    PdfPCell cell =
        new PdfPCell(
            new Phrase(
                "Cautela técnica (aparte, NO es incumplimiento): "
                    + adv
                    + " edificaciones CUMPLEN pero quedan cerca del límite (< 1,5 × franja). Revisar"
                    + " en campo por el error de la geometría catastral.",
                font(9, Font.NORMAL, GRIS)));
    cell.setBackgroundColor(new Color(0xF2, 0xF2, 0xF2));
    cell.setBorderColor(GRIS);
    cell.setPadding(6);
    c.addCell(cell);
    doc.add(c);
    doc.add(
        new Paragraph(
            f50 + " edificaciones están en pendiente > 30 % y se les aplica la franja de 50 m.",
            font(8.5f, Font.NORMAL, GRIS)));
    doc.add(espacio(8));
  }

  private Image mapa(PdfWriter writer, String ine) {
    List<Map<String, Object>> bordes =
        jdbc.queryForList(
            "select (dp).path[1] ring, st_x((dp).geom) x, st_y((dp).geom) y"
                + " from (select st_dumppoints(st_boundary(st_simplifypreservetopology(geom,"
                + "   greatest((st_xmax(geom) - st_xmin(geom)) / 300.0, 1)))) dp"
                + "   from municipio where ine_code = ?) s order by (dp).path[1], (dp).path[2]",
            ine);
    List<Map<String, Object>> puntos =
        jdbc.queryForList(
            "select st_x(st_pointonsurface(e.geom)) x, st_y(st_pointonsurface(e.geom)) y, w.clase"
                + " from edificacion e join wui_edificacion w on w.ref_catastral = e.ref_catastral"
                + " where e.ine_code = ?",
            ine);

    float w = 470;
    float h = 360;
    PdfTemplate tpl = writer.getDirectContent().createTemplate(w, h);
    double[] bb = bbox(bordes);
    if (bb != null) {
      double escala = Math.min((w - 20) / (bb[2] - bb[0]), (h - 20) / (bb[3] - bb[1]));
      double offx = (w - (bb[2] - bb[0]) * escala) / 2;
      double offy = (h - (bb[3] - bb[1]) * escala) / 2;

      tpl.setColorStroke(new Color(0x55, 0x55, 0x55));
      tpl.setLineWidth(0.7f);
      int anilloActual = Integer.MIN_VALUE;
      for (Map<String, Object> p : bordes) {
        int ring = ((Number) p.get("ring")).intValue();
        float px = (float) (((Double) p.get("x") - bb[0]) * escala + offx);
        float py = (float) (((Double) p.get("y") - bb[1]) * escala + offy);
        if (ring != anilloActual) {
          if (anilloActual != Integer.MIN_VALUE) {
            tpl.stroke();
          }
          tpl.moveTo(px, py);
          anilloActual = ring;
        } else {
          tpl.lineTo(px, py);
        }
      }
      tpl.stroke();

      for (Map<String, Object> p : puntos) {
        float px = (float) (((Double) p.get("x") - bb[0]) * escala + offx);
        float py = (float) (((Double) p.get("y") - bb[1]) * escala + offy);
        tpl.setColorFill(colorDe(str(p.get("clase"))));
        tpl.circle(px, py, 1.7f);
        tpl.fill();
      }
    }
    Image img = Image.getInstance(tpl);
    img.setAlignment(Element.ALIGN_CENTER);
    return img;
  }

  private void tabla(Document doc, List<Map<String, Object>> edifs) {
    doc.add(espacio(8));
    doc.add(
        new Paragraph(
            "Edificaciones por distancia ascendente al monte", font(11, Font.BOLD, TINTA)));
    doc.add(
        new Paragraph(
            "Solo referencia catastral y coordenada. Sin titulares ni direcciones.",
            font(8, Font.ITALIC, GRIS)));
    PdfPTable t = new PdfPTable(new float[] {2.4f, 1.4f, 1.4f, 1.6f, 1.1f, 2.2f});
    t.setWidthPercentage(100);
    t.setHeaderRows(1);
    for (String c :
        new String[] {"Ref. catastral", "Lat", "Lon", "Clase", "Franja", "Motivo franja"}) {
      t.addCell(cabecera(c, TINTA));
    }
    for (Map<String, Object> e : edifs) {
      String clase = str(e.get("clase"));
      long franja = num(e.get("franja"));
      Object pend = e.get("pend");
      String motivo =
          franja == 50
              ? "pendiente " + str(pend) + " % (> 30)"
              : "pendiente " + str(pend) + " % (<= 30)";
      t.addCell(celda(str(e.get("ref")), TINTA));
      t.addCell(celda(str(e.get("lat")), TINTA));
      t.addCell(celda(str(e.get("lon")), TINTA));
      t.addCell(celda(etiqueta(clase, e.get("adv")), colorDe(clase)));
      t.addCell(celda(franja + " m", TINTA));
      t.addCell(celda(motivo, GRIS));
    }
    doc.add(t);
  }

  // --- helpers ---

  private static double[] bbox(List<Map<String, Object>> pts) {
    if (pts.isEmpty()) {
      return null;
    }
    double minx = Double.MAX_VALUE,
        miny = Double.MAX_VALUE,
        maxx = -Double.MAX_VALUE,
        maxy = -Double.MAX_VALUE;
    for (Map<String, Object> p : pts) {
      double x = (Double) p.get("x");
      double y = (Double) p.get("y");
      minx = Math.min(minx, x);
      miny = Math.min(miny, y);
      maxx = Math.max(maxx, x);
      maxy = Math.max(maxy, y);
    }
    if (maxx - minx < 1 || maxy - miny < 1) {
      return null;
    }
    return new double[] {minx, miny, maxx, maxy};
  }

  private static Color colorDe(String clase) {
    return switch (clase) {
      case "critico" -> ROJO;
      case "incumple" -> AMBAR;
      default -> VERDE;
    };
  }

  private static String etiqueta(String clase, Object adv) {
    String base =
        switch (clase) {
          case "critico" -> "Crítico";
          case "incumple" -> "Incumple";
          default -> "Cumple";
        };
    return Boolean.TRUE.equals(adv) ? base + " ·cautela" : base;
  }

  private static Font font(float size, int style, Color color) {
    Font f = new Font(Font.HELVETICA, size, style);
    f.setColor(color);
    return f;
  }

  private static Paragraph espacio(float alto) {
    Paragraph p = new Paragraph(" ");
    p.setSpacingAfter(alto);
    return p;
  }

  private static PdfPCell cabecera(String texto, Color color) {
    PdfPCell c = new PdfPCell(new Phrase(texto, font(9, Font.BOLD, color)));
    c.setBackgroundColor(new Color(0xEC, 0xEC, 0xEC));
    c.setPadding(4);
    return c;
  }

  private static PdfPCell aviso(String texto) {
    PdfPCell c = new PdfPCell(new Phrase(texto, font(8.5f, Font.NORMAL, TINTA)));
    c.setPadding(5);
    c.setBorderColor(new Color(0xCC, 0xCC, 0xCC));
    return c;
  }

  private static PdfPCell celda(String texto, Color color) {
    PdfPCell c = new PdfPCell(new Phrase(texto, font(8, Font.NORMAL, color)));
    c.setPadding(3);
    return c;
  }

  private static PdfPCell celdaClase(String texto, Color color) {
    PdfPCell c = new PdfPCell(new Phrase(texto, font(9.5f, Font.NORMAL, color)));
    c.setPadding(5);
    return c;
  }

  private static PdfPCell celdaNum(long n, Color color) {
    PdfPCell c = new PdfPCell(new Phrase(Long.toString(n), font(11, Font.BOLD, color)));
    c.setHorizontalAlignment(Element.ALIGN_RIGHT);
    c.setPadding(5);
    return c;
  }

  private static long num(Object o) {
    return o == null ? 0 : ((Number) o).longValue();
  }

  private static String str(Object o) {
    return o == null ? "" : o.toString();
  }
}
