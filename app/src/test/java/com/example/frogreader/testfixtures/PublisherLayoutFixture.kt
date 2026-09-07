package com.example.frogreader.testfixtures

/**
 * A deliberately generic publisher-layout sample shared by parser and reader
 * tests.  It mirrors the structure used by illustrated textbooks without
 * relying on any title-specific class names:
 *
 *  * one decorated container owns a continuous background, border and padding;
 *  * a ruled heading and several paragraphs remain children of that container;
 *  * a nested floated figure sits beside the following prose;
 *  * a named publication font is inherited through multiple wrappers; and
 *  * a real grid requests a percentage width and asymmetric columns.
 *
 * Keep the fixture structural. Production code must interpret the CSS rather
 * than special-case these class names.
 */
internal object PublisherLayoutFixture {
    const val FONT_FAMILY = "publisher serif"
    val panelBackgroundArgb: Int = 0xffd2e6c5.toInt()
    val panelBorderArgb: Int = 0xff3baa35.toInt()
    val headingBorderArgb: Int = 0xff202020.toInt()

    val css: String =
        """
        @font-face {
            font-family: "Publisher Serif";
            src: url("fonts/publisher-serif.ttf");
        }

        .publication {
            font-family: "Publisher Serif", serif;
        }

        .publisher-panel {
            margin: 1em 0;
            padding: 0.5em 0.75em 0.6em;
            background-color: #d2e6c5;
            border: 0.125em solid #3baa35;
        }

        .publisher-heading {
            margin: 0 0 0.4em;
            padding-bottom: 0.2em;
            border-bottom: 0.125em solid #202020;
            font-weight: bold;
        }

        .publisher-float {
            float: left;
            width: 38%;
            margin: 0 0.75em 0.35em 0;
        }

        .publisher-float img {
            width: 100%;
        }

        .publisher-caption {
            margin: 0.25em 0 0;
            font-size: 0.8em;
        }

        .publisher-grid {
            width: 72%;
            margin: 0.8em auto 0;
            border-collapse: collapse;
        }

        .publisher-grid th,
        .publisher-grid td {
            border: 0.0625em solid #3baa35;
            padding: 0.25em;
        }

        .publisher-grid .label-column { width: 25%; }
        .publisher-grid .value-column { width: 75%; }
        """.trimIndent()

    val html: String =
        """
        <section class="publication">
          <p id="before-panel">Before the panel.</p>
          <div id="publisher-panel" class="publisher-panel">
            <h3 id="publisher-heading" class="publisher-heading">Worked example</h3>
            <div id="publisher-float" class="publisher-float">
              <p><img src="figure.png" alt="A labelled diagram"/></p>
              <p id="publisher-caption" class="publisher-caption">Figure caption.</p>
            </div>
            <p id="panel-first">First paragraph beside the floated figure.</p>
            <p id="panel-second">Second paragraph remains in the same decorated box.</p>
            <table id="publisher-grid" class="publisher-grid">
              <thead>
                <tr><th class="label-column">Term</th><th class="value-column">Meaning</th></tr>
              </thead>
              <tbody>
                <tr><td>One</td><td>The first value</td></tr>
                <tr><td>Two</td><td>The second value</td></tr>
              </tbody>
            </table>
          </div>
          <p id="after-panel">After the panel.</p>
        </section>
        """.trimIndent()
}
