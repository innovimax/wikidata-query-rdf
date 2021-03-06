package org.wikidata.query.rdf.blazegraph.label;

import static org.hamcrest.Matchers.both;
import static org.hamcrest.Matchers.containsString;
import static org.wikidata.query.rdf.test.Matchers.assertResult;
import static org.wikidata.query.rdf.test.Matchers.binds;
import static org.wikidata.query.rdf.test.Matchers.notBinds;

import java.util.Locale;

import org.apache.log4j.Logger;
import org.junit.Test;
import org.openrdf.model.impl.LiteralImpl;
import org.openrdf.query.QueryEvaluationException;
import org.openrdf.query.TupleQueryResult;
import org.wikidata.query.rdf.blazegraph.AbstractRandomizedBlazegraphTestBase;
import org.wikidata.query.rdf.common.uri.Ontology;
import org.wikidata.query.rdf.common.uri.RDFS;
import org.wikidata.query.rdf.common.uri.SKOS;
import org.wikidata.query.rdf.common.uri.SchemaDotOrg;

public class LabelServiceUnitTest extends AbstractRandomizedBlazegraphTestBase {
    private static final Logger log = Logger.getLogger(LabelServiceUnitTest.class);

    @Test
    public void labelOverConstant() throws QueryEvaluationException {
        simpleLabelLookupTestCase(null, "wd:Q123");
    }

    @Test
    public void labelOverVariable() throws QueryEvaluationException {
        add("ontology:dummy", "ontology:dummy", "wd:Q123");
        simpleLabelLookupTestCase("ontology:dummy ontology:dummy ?o.", "?o");
    }

    @Test
    public void chain() throws QueryEvaluationException {
        add("ontology:dummy", "ontology:dummy", "wd:Q1");
        add("wd:Q1", "ontology:dummy", "wd:Q2");
        add("wd:Q2", "ontology:dummy", "wd:Q3");
        add("wd:Q3", "ontology:dummy", "wd:Q4");
        add("wd:Q4", "ontology:dummy", "wd:Q123");
        simpleLabelLookupTestCase(
                "ontology:dummy ontology:dummy/ontology:dummy/ontology:dummy/ontology:dummy/ontology:dummy ?o.", "?o");
    }

    @Test
    public void many() throws QueryEvaluationException {
        for (int i = 1; i <= 10; i++) {
            addSimpleLabels("Q" + i);
            add("ontology:dummy", "ontology:dummy", "wd:Q" + i);
        }
        TupleQueryResult result = lookupLabel("ontology:dummy ontology:dummy ?o", "en", "?o", "rdfs:label");
        for (int i = 1; i <= 10; i++) {
            assertTrue(result.hasNext());
            assertThat(result.next(), binds("oLabel", new LiteralImpl("in en", "en")));
        }
        assertFalse(result.hasNext());
    }

    @Test
    public void labelOverUnboundSubject() throws QueryEvaluationException {
        TupleQueryResult result = lookupLabel(null, "en", "?s", "rdfs:label");
        assertThat(result.next(), notBinds("sLabel"));
        assertFalse(result.hasNext());
    }

    @Test
    public void noDotIsOkErrorMessage() {
        try {
            StringBuilder query = Ontology.prefix(new StringBuilder());
            query.append("SELECT *\n");
            query.append("WHERE {\n");
            query.append("  SERVICE ontology:label {}\n");
            query.append("}\n");
            query(query.toString());
            fail();
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage(), containsString("must provide the label service a list of languages"));
        }
    }

    @Test
    public void deeperServiceCall() {
        add("ontology:dummy", "ontology:dummy", "wd:Q1");
        add("wd:Q1", "ontology:dummy", "wd:Q123");
        addSimpleLabels("Q123");
        StringBuilder query = uris().prefixes(Ontology.prefix(new StringBuilder()));
        query.append("SELECT ?pLabel\n");
        query.append("WHERE {\n");
        query.append("  ontology:dummy ontology:dummy ?s .\n");
        query.append("  {\n");
        query.append("    ?s ontology:dummy ?p .\n");
        query.append("    SERVICE ontology:label { bd:serviceParam ontology:language \"en,de\" . }\n");
        query.append("  }\n");
        query.append("}\n");
        assertResult(query(query.toString()), binds("pLabel", "in en", "en"));
    }

    private void simpleLabelLookupTestCase(String extraQuery, String subjectInQuery) throws QueryEvaluationException {
        addSimpleLabels("Q123");
        slltcp(extraQuery, subjectInQuery, "en", "in en", "en", "alt label in en, alt label in en2", "en");
        slltcp(extraQuery, subjectInQuery, "ru", "in ru", "ru", null, null);
        slltcp(extraQuery, subjectInQuery, "dummy", "Q123", null, null, null);
        slltcp(extraQuery, subjectInQuery, "dummy.en", "in en", "en", "alt label in en, alt label in en2", "en");
        slltcp(extraQuery, subjectInQuery, "en.ru", "in en", "en", "alt label in en, alt label in en2", "en");
        slltcp(extraQuery, subjectInQuery, "ru.de", "in ru", "ru", "alt label in de", "de");
    }

    private void slltcp(String extraQuery, String subjectInQuery, String language, String labelText,
            String labelLanguage, String altLabelText, String altLabelLanguage) throws QueryEvaluationException {
        assertResult(
                lookupLabel(extraQuery, language, subjectInQuery, "rdfs:label", "skos:altLabel"),
                both(
                        binds(labelName(subjectInQuery, "rdfs:label"), labelText, labelLanguage)
                    ).and(
                        binds(labelName(subjectInQuery, "skos:altLabel"), altLabelText, altLabelLanguage)
                    )
        );

    }

    private String languageParams(String inLanguages) {
        String[] langs;
        StringBuilder params = new StringBuilder();
        if (inLanguages.contains(".")) {
            langs = inLanguages.split("\\.");
        } else {
            langs = new String[] {inLanguages};
        }
        for (String lang: langs) {
            params.append("bd:serviceParam ontology:language \"" + lang + "\".\n");
        }
        return params.toString();
    }

    private TupleQueryResult lookupLabel(String otherQuery, String inLanguages, String subject, String... labelTypes)
            throws QueryEvaluationException {
        if (inLanguages.indexOf(' ') >= 0) {
            throw new IllegalArgumentException("Languages cannot contain a space or that'd make an invalid query.");
        }
        StringBuilder query = uris().prefixes(
                SchemaDotOrg.prefix(SKOS.prefix(RDFS.prefix(Ontology.prefix(new StringBuilder())))));
        query.append("SELECT");
        for (String labelType : labelTypes) {
            query.append(" ?").append(labelName(subject, labelType));
        }
        query.append('\n');
        query.append("WHERE {\n");
        if (otherQuery != null) {
            query.append(otherQuery).append("\n");
        }
        query.append("  SERVICE ontology:label {\n").append(languageParams(inLanguages));
        if (subject.contains(":") || rarely()) {
            // We rarely explicitly specify the labels to load
            for (String labelType : labelTypes) {
                query.append("    ").append(subject).append(" ").append(labelType).append(" ?")
                        .append(labelName(subject, labelType)).append(" .\n");
            }
        }
        query.append("  }\n");
        query.append("}\n");
        if (log.isDebugEnabled()) {
            log.debug("Query:  " + query);
        }
        log.warn("Running query: " + query.toString());
        return query(query.toString());
    }

    private void addSimpleLabels(String entity) {
        for (String language : new String[] {"en", "de", "ru"}) {
            add("wd:" + entity, RDFS.LABEL, new LiteralImpl("in " + language, language));
        }
        add("wd:" + entity, SKOS.ALT_LABEL, new LiteralImpl("alt label in en", "en"));
        add("wd:" + entity, SKOS.ALT_LABEL, new LiteralImpl("alt label in en2", "en"));
        add("wd:" + entity, SKOS.ALT_LABEL, new LiteralImpl("alt label in de", "de"));
        for (String language : new String[] {"en", "de", "ru"}) {
            add("wd:" + entity, SchemaDotOrg.DESCRIPTION, new LiteralImpl("description in " + language, language));
        }
    }

    private String labelName(String subjectName, String labelType) {
        int start = labelType.indexOf(':') + 1;
        if (subjectName.contains(":")) {
            return labelType.substring(start);
        }
        return subjectName.substring(1) + labelType.substring(start, start + 1).toUpperCase(Locale.ROOT)
                + labelType.substring(start + 1);
    }

    @Test
    public void labelOnAsk() {
        StringBuilder query = uris().prefixes(Ontology.prefix(new StringBuilder()));
        query.append("ASK {\n");
        query.append("  ontology:dummy ontology:dummy ?s .\n");
        query.append("  SERVICE ontology:label { bd:serviceParam ontology:language \"en,de\" . }\n");
        query.append("}\n");
        assertFalse(ask(query.toString()));
    }

}
