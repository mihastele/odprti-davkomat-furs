package si.opendavki.fx;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/** All known rates by date. Parses the ECB {@code Cube} XML layout or a {@code date,currency,rate} CSV. */
public final class RateTable {
    private final NavigableMap<LocalDate, Map<String, BigDecimal>> byDate = new TreeMap<>();

    public Optional<BigDecimal> exact(String currency, LocalDate date) {
        Map<String, BigDecimal> day = byDate.get(date);
        return day == null ? Optional.empty() : Optional.ofNullable(day.get(currency));
    }

    /** Latest date with a rate for the currency on or before {@code date}, no earlier than {@code earliest}. */
    public Optional<LocalDate> latestOnOrBefore(String currency, LocalDate date, LocalDate earliest) {
        for (LocalDate d : byDate.subMap(earliest, true, date, true).descendingKeySet()) {
            if (byDate.get(d).containsKey(currency)) return Optional.of(d);
        }
        return Optional.empty();
    }

    public Optional<LocalDate> lastDate() {
        return byDate.isEmpty() ? Optional.empty() : Optional.of(byDate.lastKey());
    }

    public int days() { return byDate.size(); }

    private void put(LocalDate d, String currency, BigDecimal rate) {
        if (rate.signum() <= 0) throw new IllegalArgumentException("rate must be > 0 for " + currency + " on " + d);
        BigDecimal old = byDate.computeIfAbsent(d, k -> new HashMap<>()).put(currency, rate);
        if (old != null && old.compareTo(rate) != 0) {
            throw new IllegalArgumentException("conflicting rates for " + currency + " on " + d + ": " + old + " vs " + rate);
        }
    }

    /** Auto-detects XML (starts with '&lt;') versus CSV. */
    public static RateTable parse(byte[] content) throws IOException {
        String head = new String(content, 0, Math.min(content.length, 64), StandardCharsets.UTF_8).stripLeading();
        if (head.startsWith("﻿")) head = head.substring(1).stripLeading();
        return head.startsWith("<") ? parseEcbXml(content) : parseCsv(new String(content, StandardCharsets.UTF_8));
    }

    /** ECB layout: {@code <Cube time="2024-03-01"><Cube currency="USD" rate="1.0834"/>...}. */
    public static RateTable parseEcbXml(byte[] xml) throws IOException {
        RateTable t = new RateTable();
        try {
            XMLInputFactory f = XMLInputFactory.newFactory();
            f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            f.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            XMLStreamReader r = f.createXMLStreamReader(new ByteArrayInputStream(xml));
            LocalDate day = null;
            while (r.hasNext()) {
                if (r.next() != XMLStreamConstants.START_ELEMENT || !r.getLocalName().equals("Cube")) continue;
                String time = r.getAttributeValue(null, "time");
                String currency = r.getAttributeValue(null, "currency");
                if (time != null) {
                    day = LocalDate.parse(time);
                } else if (currency != null) {
                    if (day == null) throw new IOException("rate element outside a dated Cube");
                    t.put(day, currency, new BigDecimal(r.getAttributeValue(null, "rate")));
                }
            }
        } catch (XMLStreamException | RuntimeException e) {
            throw new IOException("cannot parse rate XML: " + e.getMessage(), e);
        }
        if (t.byDate.isEmpty()) throw new IOException("rate XML contains no rates");
        return t;
    }

    /** CSV with header {@code date,currency,rate}; rate = foreign units per 1 EUR. */
    public static RateTable parseCsv(String text) throws IOException {
        RateTable t = new RateTable();
        String[] lines = text.split("\\R");
        int n = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (n++ == 0) {
                if (!line.toLowerCase(java.util.Locale.ROOT).replace(" ", "").equals("date,currency,rate")) {
                    throw new IOException("rate CSV must start with the header 'date,currency,rate'");
                }
                continue;
            }
            String[] c = line.split(",");
            if (c.length != 3) throw new IOException("line " + (i + 1) + ": expected date,currency,rate");
            try {
                t.put(LocalDate.parse(c[0].trim()), c[1].trim().toUpperCase(java.util.Locale.ROOT), new BigDecimal(c[2].trim()));
            } catch (RuntimeException e) {
                throw new IOException("line " + (i + 1) + ": " + e.getMessage(), e);
            }
        }
        if (t.byDate.isEmpty()) throw new IOException("rate CSV contains no rates");
        return t;
    }
}
