package com.example.frogreader.ui.reader

/** Public-domain source fragments and exact matching CSS from the audited EPUBs. */
internal object AdvancedEpubFixtures {
    val coreCss = """
@charset "utf-8";
@namespace epub "http://www.idpf.org/2007/ops";

body{
	font-variant-numeric: oldstyle-nums;
	hyphens: auto;
	text-wrap: pretty;
}

p{
	margin: 0;
	text-indent: 1em;
}

hr{
	border: none;
	border-top: 1px solid;
	height: 0;
	margin: 1.5em auto;
	width: 25%;
}

q::before,
q::after{
	content: "";
}

blockquote{
	margin: 1em 2.5em;
}

h1,
h2,
h3,
h4,
h5,
h6,
hgroup{
	break-after: avoid;
	break-inside: avoid;
	font-variant: small-caps;
	hyphens: none;
	margin: 3em 0;
	text-align: center;
}

hgroup:has(h1){
	margin: 6em 0;
}

hgroup:has(h2){
	margin: 4.5em 0;
}

hgroup:has(h3){
	margin: 3.51em 0;
}

hgroup:has(h4){
	margin: 3em 0;
}

hgroup:has(h5){
	margin: 2.49em 0;
}

hgroup:has(h6){
	margin: 2.01em 0;
}

hgroup > *{
	margin: 0;
}

/* Simulate `<h2>` in an `<hgroup>`. */
hgroup > h1 + p{
	font-size: 1.5em;
}

/* Simulate `<h3>` in an `<hgroup>`. */
hgroup > h2 + p{
	font-size: 1.17em;
}

/* Simulate `<h4>` in an `<hgroup>`. */
hgroup > h2 + p + p,
hgroup > h3 + p{
	font-size: 1em;
}

/* Simulate `<h5>` in an `<hgroup>`. */
hgroup > h2 + p + p + p,
hgroup > h3 + p + p,
hgroup > h4 + p{
	font-size: .83em;
}

/* Simulate `<h6>` in an `<hgroup>`. */
hgroup > h2 + p + p + p + p,
hgroup > h3 + p + p + p,
hgroup > h4 + p + p,
hgroup > h5 + p{
	font-size: .67em;
}

hgroup > p:first-child{
	font-weight: bold;
}

hgroup > p{
	text-indent: 0;
}

p.continued,
h2 + p,
h3 + p,
h4 + p,
h5 + p,
h6 + p,
header + p,
hr + p,
hgroup + p,
p:first-child{
	hanging-punctuation: first last;
	text-indent: 0;
}

cite{
	font-style: normal;
}

abbr{
	border: none;
	white-space: nowrap;
}

blockquote cite{
	display: block;
	font-style: italic;
	text-align: right;
}

blockquote cite i{
	font-style: normal;
}

b,
strong{
	font-variant: small-caps;
	font-weight: normal;
}

i > i,
em > i,
i > em{
	font-style: normal;
}

ol,
ul{
	margin-bottom: 1em;
	margin-top: 1em;
}

header{
	break-after: avoid;
	break-inside: avoid;
	hyphens: none;
	text-align: center;
}

header > * + p{
	text-indent: 0;
}

article > header + *,
section > header + *{
	margin-top: 3em;
}

a[epub|type~="noteref"]{
	font-size: smaller;
	font-style: normal !important;
	vertical-align: super;
}

section[epub|type~="endnotes"] > ol > li{
	margin: 1em 0;
}

/* Invert images in dark mode. RMSDK requires a target media as well as a state. */
@media all and (prefers-color-scheme: dark){
	img[epub|type~="se:image.color-depth.black-on-transparent"]{
		filter: invert(100%);
	}

	img[epub|type~="se:image.color-depth.black-on-transparent"][epub|type~="se:image.style.realistic"]{
		background: currentColor;
		filter: none;
	}
}

"""

    val dramaHtml = """
<html xmlns:epub="http://www.idpf.org/2007/ops"><body epub:type="z3998:drama"><section id="act-1" epub:type="z3998:scene"><table><tbody><tr>
						<td/>
						<td>
							<i epub:type="z3998:stage-direction"><b epub:type="z3998:persona">Brigid</b> and <b epub:type="z3998:persona">Beatrice Justice</b> come in by the door on the left. <b epub:type="z3998:persona">Brigid</b> is an elderly woman, lowsized, with irongrey hair. <b epub:type="z3998:persona">Beatrice Justice</b> is a slender dark young woman of 27 years. She wears a wellmade navyblue costume and an elegant simply trimmed black straw hat, and carries a small portfolioshaped handbag.</i>
						</td>
					</tr><tr>
						<td epub:type="z3998:persona">Brigid</td>
						<td>The mistress and Master Archie is at the bath. They never expected you. Did you send word you were back, Miss Justice?</td>
					</tr><tr>
						<td epub:type="z3998:persona">Beatrice</td>
						<td>No. I arrived just now.</td>
					</tr><tr>
						<td epub:type="z3998:persona">Brigid</td>
						<td><i epub:type="z3998:stage-direction">Points to the easychair.</i> Sit down and I’ll tell the master you are here. Were you long in the train?</td>
					</tr><tr>
						<td epub:type="z3998:persona">Beatrice</td>
						<td><i epub:type="z3998:stage-direction">Sitting down.</i> Since morning.</td>
					</tr><tr>
						<td epub:type="z3998:persona">Brigid</td>
						<td>Master Archie got your postcard with the views of Youghal. You’re tired out, I’m sure.</td>
					</tr><tr>
						<td epub:type="z3998:persona">Beatrice</td>
						<td>O, no. <i epub:type="z3998:stage-direction">She coughs rather nervously.</i> Did he practise the piano while I was away?</td>
					</tr><tr>
						<td epub:type="z3998:persona">Brigid</td>
						<td><i epub:type="z3998:stage-direction">Laughs heartily.</i> Practice, how are you! Is it Master Archie? He is mad after the milkman’s horse now. Had you nice weather down there, Miss Justice?</td>
					</tr></tbody></table></section></body></html>
"""

    val dramaCss = """
@charset "utf-8";
@namespace epub "http://www.idpf.org/2007/ops";

[epub|type~="z3998:drama"] table{
	border-collapse: collapse;
	margin: 1em auto;
	width: 100%;
}

[epub|type~="z3998:drama"] tr:first-child td{
	padding-top: 0;
}

[epub|type~="z3998:drama"] tr:last-child td{
	padding-bottom: 0;
}

[epub|type~="z3998:drama"] td{
	padding: .5em;
	vertical-align: top;
}

[epub|type~="z3998:drama"] td:last-child{
	padding-right: 0;
}

[epub|type~="z3998:drama"] td:first-child{
	padding-left: 0;
}

[epub|type~="z3998:drama"] td[epub|type~="z3998:persona"]{
	hyphens: none;
	-epub-hyphens: none;
	text-align: right;
	width: 20%;
}

[epub|type~="z3998:stage-direction"]{
	font-style: italic;
}

[epub|type~="z3998:stage-direction"] [epub|type~="z3998:persona"],
i [epub|type~="z3998:persona"]{
	font-style: normal;
}

[epub|type~="z3998:stage-direction"]::before{
	content: "(";
	font-style: normal;
}

[epub|type~="z3998:stage-direction"]::after{
	content: ")";
	font-style: normal;
}

[epub|type~="z3998:persona"]{
	font-variant: all-small-caps;
}

section[epub|type~="z3998:scene"] > p{
	margin: 1em auto;
	width: 75%;
}

[epub|type~="z3998:dramatis-personae"]{
	text-align: center;
}

[epub|type~="z3998:dramatis-personae"] p{
	text-indent: 0;
}

[epub|type~="z3998:dramatis-personae"] ul{
	list-style: none;
	margin: 0;
	padding: 0;
}

[epub|type~="z3998:dramatis-personae"] ul li{
	font-style: italic;
	margin: 1em;
}

[epub|type~="z3998:dramatis-personae"] ul + p{
	margin-top: 2em;
}

q{
	font-style: italic;
}

@media(max-width: 400px){
	[epub|type~="z3998:drama"] td,
	[epub|type~="z3998:drama"] td[epub|type~="z3998:persona"],
	[epub|type~="z3998:drama"] td:last-child{
		display: block;
		padding: 0;
		text-align: initial;
		width: 100%;
	}

	[epub|type~="z3998:drama"] td[epub|type~="z3998:persona"]{
		break-after: avoid;
		margin-top: 1.5em;
	}

	/* Rows that are only stage direction get a top margin. */
	[epub|type~="z3998:drama"] td:first-child:not([epub|type]) + td{
		margin-top: 1.5em;
	}
}

"""

    val mathHtml = """
<html xmlns:epub="http://www.idpf.org/2007/ops"><body><section id="appendix-5"><figure id="figure-1">
					<table>
						<tbody>
							<tr>
								<td>×</td>
								<td>×</td>
								<td>×</td>
								<td>×</td>
								<td>×</td>
								<td>×</td>
								<td>×</td>
								<td>×</td>
								<td>×</td>
								<td>○</td>
							</tr>
							<tr>
								<td>×</td>
								<td>×</td>
								<td>×</td>
								<td>×</td>
								<td>×</td>
								<td/>
								<td>○</td>
								<td>○</td>
								<td>○</td>
								<td>○</td>
							</tr>
							<tr>
								<td>×</td>
								<td>×</td>
								<td>○</td>
								<td>○</td>
								<td>○</td>
								<td>○</td>
								<td>○</td>
								<td>○</td>
								<td>○</td>
								<td>○</td>
							</tr>
						</tbody>
					</table>
				</figure></section></body></html>
"""

    val mathCss = """
@charset "utf-8";
@namespace epub "http://www.idpf.org/2007/ops";
@namespace xml "http://www.w3.org/XML/1998/namespace";

var{
	font-style: italic;
}

figure{
	break-inside: avoid;
	margin: 1em 2.5em;
}

figure img{
	display: block;
	margin: auto;
	max-height: 100vh;
	max-width: 100%;
}

footer{
	margin-top: 1em;
	text-align: right;
}

[epub|type~="z3998:signature"]{
	font-variant: small-caps;
}

blockquote header{
	font-style: italic;
	margin-bottom: 1em;
	text-align: center;
}

[epub|type~="z3998:poem"] p,
[epub|type~="z3998:verse"] p{
	text-align: initial;
	text-indent: 0;
}

[epub|type~="z3998:poem"] p + p{
	margin-top: 1em;
}

[epub|type~="z3998:poem"] p > span,
[epub|type~="z3998:verse"] p > span{
	display: block;
	padding-left: 1em;
	text-indent: -1em;
}

[epub|type~="z3998:poem"] p > span + br,
[epub|type~="z3998:verse"] p > span + br{
	display: none;
}

p span.i1{
	padding-left: 2em;
	text-indent: -1em;
}

/* All epigraphs */
[epub|type~="epigraph"]{
	font-style: italic;
	hyphens: none;
	-epub-hyphens: none;
}

[epub|type~="epigraph"] cite{
	font-style: normal;
	font-variant: small-caps;
	margin-top: 1em;
}
/* End all epigraphs */

/* Full-page epigraphs */
section[epub|type~="epigraph"]{
	text-align: center;
}

section[epub|type~="epigraph"] > *{
	display: inline-block;
	margin: auto;
	margin-top: 3em;
	max-width: 80%;
	text-align: initial;
}

@supports(display: flex){
	section[epub|type~="epigraph"]{
		align-items: center;
		box-sizing: border-box;
		display: flex;
		flex-direction: column;
		justify-content: center;
		min-height: calc(98vh - 3em);
		padding-top: 3em;
	}

	section[epub|type~="epigraph"] > *{
		margin: 0;
	}
}
/* End full-page epigraphs */

/* Epigraphs in section headers */
section > header [epub|type~="epigraph"]{
	display: inline-block;
	margin: auto;
	max-width: 80%;
	text-align: initial;
}

@supports(display: table){
	section > header [epub|type~="epigraph"]{
		display: table;
	}
}
/* End epigraphs in section headers */

#chapter-10 > p:last-child{
	margin-top: 1em;
	text-align: center;
	text-indent: 0;
}

#appendix ol{
	list-style-type: lower-roman;
}

#appendix .class-list ol{
	list-style-type: upper-roman;
	margin: 0 0 0 1.5em;
	padding: 0;
}

#appendix .class-list ol > li{
	margin-bottom: 1em;
}

#appendix .class-list ul{
	list-style: none;
	margin: 0;
	padding: 0;
}

#appendix .class-list ul ul{
	margin-bottom: .5em;
}

#illustration-2 figcaption{
	margin-bottom: 1em;
	text-align: initial;
}

@supports(display: flex){
	#illustration-2{
		align-items: center;
		display: flex;
	}

	#illustration-2 figcaption{
		flex-basis: 40%;
		margin: 0 2em 0 0;
	}

	#illustration-2 img{
		flex-basis: 60%;
	}
}

#illustration-3 img{
	max-width: 10em;
}

@supports(display: flex){
	#appendix-2-2 > div{
		align-items: center;
		display: flex;
		flex-direction: row-reverse;
	}

	#appendix-2-2 > div figure{
		flex-basis: 30%;
	}

	#appendix-2-2 > div div{
		flex-basis: 70%;
		margin-right: 2em;
	}
}

#appendix-5 > figure > table{
	margin: 0 auto;
}

#appendix-5 figure td{
	text-align: center;
	width: 2em;
}

#appendix-7-1 ol{
	list-style-type: lower-alpha;
}

"""

    val arithmeticHtml = """
<html xmlns:epub="http://www.idpf.org/2007/ops"><body><section id="chapter-6"><table>
				<tbody>
					<tr>
						<td>365</td>
					</tr>
					<tr>
						<td>1</td>
					</tr>
					<tr>
						<td>364</td>
					</tr>
				</tbody>
			</table></section></body></html>
"""

    val lookingGlassCss = """
@charset "utf-8";
@namespace epub "http://www.idpf.org/2007/ops";

[epub|type~="z3998:poem"] p,
[epub|type~="z3998:song"] p,
[epub|type~="z3998:verse"] p{
	text-align: initial;
	text-indent: 0;
}

[epub|type~="z3998:poem"] p > span,
[epub|type~="z3998:song"] p > span,
[epub|type~="z3998:verse"] p > span{
	display: block;
	padding-left: 1em;
	text-indent: -1em;
}

[epub|type~="z3998:poem"] p > span + br,
[epub|type~="z3998:song"] p > span + br,
[epub|type~="z3998:verse"] p > span + br{
	display: none;
}

[epub|type~="z3998:poem"] p + p,
[epub|type~="z3998:song"] p + p,
[epub|type~="z3998:verse"] p + p{
	margin-top: 1em;
}

p span.i1{
	padding-left: 2em;
	text-indent: -1em;
}

p span.i2{
	padding-left: 3em;
	text-indent: -1em;
}

[epub|type~="z3998:poem"] header p{
	font-variant: small-caps;
	margin-bottom: 1em;
	margin-top: 3em;
	text-align: center;
}

#illustration-10{
	max-width: 25em;
}

#chapter-6 table{
	font-variant-numeric: tabular-nums;
	margin: 1em auto;
	text-align: right;
}

#chapter-6 table tr:last-child td{
	border-top: 1px solid;
}

#preamble{
	text-align: center;
}

#preamble ol{
	display: inline-block;
	max-width: 50%;
	text-align: initial;
	vertical-align: top;
}

figure{
	break-inside: avoid;
	margin: 1em 2.5em;
}

figure img{
	display: block;
	margin: auto;
	max-height: 100vh;
	max-width: 100%;
}

figure.full-page{
	break-after: page;
	break-before: page;
}

figure.three-quarter-page img{
	max-width: 75%;
}

figure.half-page img{
	max-width: 50%;
}

figcaption{
	font-size: smaller;
	font-style: italic;
	margin: 1em;
}

/* All epigraphs */
[epub|type~="epigraph"],
[epub|type~="conclusion"]{
	font-style: italic;
	hyphens: none;
	-epub-hyphens: none;
}
/* End all epigraphs */

/* Full-page epigraphs */
section[epub|type~="epigraph"],
section[epub|type~="conclusion"]{
	text-align: center;
}

section[epub|type~="epigraph"] > *,
section[epub|type~="conclusion"] > *{
	display: inline-block;
	margin: auto;
	margin-top: 3em;
	max-width: 80%;
	text-align: initial;
}

@supports(display: flex){
	section[epub|type~="epigraph"],
	section[epub|type~="conclusion"]{
		align-items: center;
		box-sizing: border-box;
		display: flex;
		flex-direction: column;
		justify-content: center;
		min-height: calc(98vh - 3em);
		padding-top: 3em;
	}

	section[epub|type~="epigraph"] > *,
	section[epub|type~="conclusion"] > *{
		margin: 0;
	}
}
/* End full-page epigraphs */

"""

    val verseHtml = """
<html xmlns:epub="http://www.idpf.org/2007/ops"><body><section id="chapter-6"><blockquote epub:type="z3998:verse">
				<p>
					<span>“ ’Twas brillig, and the slithy toves</span>
					<br/>
					<span class="i1">Did gyre and gimble in the wabe;</span>
					<br/>
					<span>All mimsy were the borogoves,</span>
					<br/>
					<span class="i2">And the mome raths outgrabe.”</span>
				</p>
			</blockquote></section></body></html>
"""

    val blankHtml = """
<html xmlns:epub="http://www.idpf.org/2007/ops"><body>
		<section data-parent="part-6" id="chapter-6-38" epub:type="chapter">
			<h3 epub:type="z3998:ordinal z3998:roman">XXXVIII</h3>
			<p>To conceive this right,⁠—call for pen and ink⁠—here’s paper ready to your hand.⁠⸺⁠Sit down, Sir, paint her to your own mind⁠⸺⁠as like your mistress as you can⁠⸺⁠as unlike your wife as your conscience will let you⁠—’tis all one to me⁠⸺⁠please but your own fancy in it.</p>
			<div/>
			<p>⸻Was ever anything in Nature so sweet!⁠—so exquisite!</p>
			<p>⸺⁠Then, dear Sir, how could my uncle <i>Toby</i> resist it?</p>
			<p>Thrice happy book! thou wilt have one page, at least, within thy covers, which <b>Malice</b> will not blacken, and which <b>Ignorance</b> cannot misrepresent.</p>
		</section>
	</body></html>
"""

    val blankCss = """
#chapter-6-38 > div{
	break-after: page;
	break-before: page;
	min-height: 10em;
}
"""

    val starsHtml = """
<html xmlns:epub="http://www.idpf.org/2007/ops"><body><section id="chapter-6-39"><p>⸺⁠Lord have mercy upon me,⁠—said my father to himself⁠⸺⁠<span><span>*</span> <span>*</span> <span>*</span> <span>*</span> <span>*</span> <span>*</span> <span>*</span> <span>*</span></span> <span><span>*</span> <span>*</span> <span>*</span> <span>*</span> <span>*</span> <span>*</span> <span>*</span> <span>*</span></span> <span><span>*</span> <span>*</span> <span>*</span> <span>*</span> <span>*</span> <span>*</span> <span>*</span> <span>*</span></span> <span><span>*</span> <span>*</span> <span>*</span> <span>*</span> <span>*</span> <span>*</span></span></p></section></body></html>
"""

    val braceHtml = """
<html xmlns:epub="http://www.idpf.org/2007/ops"><body><section id="chapter-7-8"><p>
				<span>Just heaven!</span>
				<span>{</span>
				<span>
					<span>What masticators!⁠—</span>
					<span>What bread!⁠—</span>
				</span>
			</p><p class="continued">and so as he finished the last mouthful of it, we entered the town of <i>Montreuil</i>.</p></section></body></html>
"""

    val shandyRowsCss = """
@charset "utf-8";
@namespace epub "http://www.idpf.org/2007/ops";

@font-face{
	font-family: "Standard Blackletter";
	font-weight: 700;
	src: url("../fonts/standard-blackletter.otf") format("opentype");
}

i b{
	font-style: normal;
}

rt{
	font-size: 1em;
	ruby-align: center;
}

footer{
	margin-top: 1em;
	text-align: right;
}

blockquote header{
	font-variant: small-caps;
	margin-bottom: 1em;
	text-align: center;
}

figure{
	break-inside: avoid;
	margin: 1em 2.5em;
}

figure img{
	display: block;
	margin: auto;
	max-height: 100vh;
	max-width: 100%;
}

figure.full-page{
	break-after: page;
	break-before: page;
}

table{
	border-collapse: collapse;
}

td{
	border: 0;
	padding: 0 .5em 0 0;
}

.blackletter{
	font-family: "Standard Blackletter", serif;
	font-variant: normal;
	font-weight: 700;
}

.short-indent{
	text-indent: 3em;
}

.indent{
	margin-left: 6.5em;
	text-indent: 0;
}

.indent + p{
	text-indent: 0;
}

[epub|type~="z3998:salutation"] + p{
	text-indent: 0;
}

[epub|type~="z3998:signature"],
[epub|type~="z3998:salutation"]{
	font-variant: small-caps;
}

[epub|type~="z3998:recipient"]{
	font-style: italic;
	font-variant: normal;
}

[epub|type~="z3998:verse"] footer{
	text-align: center;
}

[epub|type~="z3998:song"] > p,
[epub|type~="z3998:verse"] > p{
	text-align: initial;
	text-indent: 0;
}

[epub|type~="z3998:song"] > p > span,
[epub|type~="z3998:verse"] > p > span{
	display: block;
	padding-left: 1em;
	text-indent: -1em;
}

[epub|type~="z3998:song"] > p > span + br,
[epub|type~="z3998:verse"] > p > span + br{
	display: none;
}

p span.i1{
	padding-left: 2em;
	text-indent: -1em;
}

p span.i2{
	padding-left: 3em;
	text-indent: -1em;
}

/* All epigraphs */
[epub|type~="epigraph"]{
	font-style: italic;
	hyphens: none;
	-epub-hyphens: none;
}

[epub|type~="epigraph"] cite{
	font-style: normal;
	font-variant: small-caps;
	margin-top: 1em;
}

[epub|type~="epigraph"] cite i{
	font-style: italic;
}
/* End all epigraphs */

/* Full-page epigraphs */
section[epub|type~="epigraph"]{
	text-align: center;
}

section[epub|type~="epigraph"] > *{
	display: inline-block;
	margin: auto;
	margin-top: 3em;
	max-width: 80%;
	text-align: initial;
}

@supports(display: flex){
	section[epub|type~="epigraph"]{
		align-items: center;
		box-sizing: border-box;
		display: flex;
		flex-direction: column;
		justify-content: center;
		min-height: calc(98vh - 3em);
		padding-top: 3em;
	}

	section[epub|type~="epigraph"] > *{
		margin: 0;
	}
}
/* End full-page epigraphs */

[epub|type~="dedication"] footer > p:first-child{
	margin-left: 40%;
	text-align: center;
}

#chapter-1-4 > p > span{
	display: block;
}

@supports(display: flex){
	#chapter-1-4 > p > span{
		align-items: center;
		display: flex;
		margin-left: 1em;
		text-indent: 0;
	}

	#chapter-1-4 > p > span::before{
		border-bottom: 1px solid;
		content: "";
		height: 0;
		width: 20%;
	}

	#chapter-1-4 > p > span::after{
		border-bottom: 1px solid;
		content: "";
		flex-grow: 1;
		height: 0;
	}
}

#chapter-1-8 footer > p:first-child{
	font-style: italic;
}

#chapter-1-9 blockquote{
	margin-left: 0;
	margin-right: 0;
}

#chapter-1-9 blockquote > p:first-child{
	text-indent: 2em;
}

#chapter-1-12 blockquote{
	text-align: center;
}

#chapter-1-12 blockquote > p{
	display: inline-block;
	text-indent: 0;
}

#chapter-1-12 blockquote:first-of-type > p{
	border: 1px solid;
	padding: .5em 1em;
}

#chapter-2-17 blockquote header b{
	text-transform: uppercase;
}

#chapter-2-17 blockquote > header > p + p{
	font-style: italic;
	font-variant: normal;
	margin-top: .5em;
}

#chapter-2-17 blockquote > header em{
	font-style: normal;
}

#chapter-2-17 blockquote{
	margin-left: 0;
	margin-right: 0;
}

#chapter-2-17 blockquote ~ blockquote p:last-child{
	margin-top: .5em;
	text-align: center;
	text-indent: 0;
}

@supports(display: flex){
	#chapter-3-11-la > p > span{
		display: flex;
		justify-content: space-around;
		text-indent: 0;
	}
}

#chapter-4-1 header i{
	font-variant: normal;
}

@supports(display: flex){
	#chapter-4-12 p:last-child{
		align-items: center;
		display: flex;
		text-indent: 0;
	}

	#chapter-4-12 p:last-child > span:first-child > span{
		display: block;
		white-space: nowrap;
	}

	#chapter-4-12 p:last-child > span:nth-child(2){
		font-size: 2em;
		margin: 0 .25em;
	}
}

@supports(display: flex){
	#chapter-4-27 > p > b{
		align-items: center;
		display: flex;
	}

	#chapter-4-27 > p > b::after{
		border-bottom: 1px solid;
		content: "";
		flex-grow: 1;
		height: 0;
	}

	#chapter-4-27 > p > span{
		align-items: center;
		display: flex;
	}

	#chapter-4-27 > p > span::before{
		content: "​"; /* zero-width space to fix line-height of parent row */
	}

	#chapter-4-27 > p > span::after{
		border-bottom: 1px solid;
		content: "";
		flex-grow: 1;
		height: 0;
	}
}

#slawkenbergius-en [epub|type~="z3998:recipient"]{
	font-style: normal;
	text-indent: 2em;
}

#slawkenbergius-en > p:last-child{
	margin-top: 1em;
	text-align: center;
	text-indent: 0;
}

#slawkenbergius-en > p:last-child > b:first-child{
	font-variant: all-small-caps;
}

@supports(display: flex){
	#chapter-5-1-the-fragment p:first-of-type{
		text-indent: 0;
	}

	#chapter-5-1-the-fragment p:first-of-type > span{
		display: flex;
		justify-content: space-between;
	}
}

@supports(display: flex){
	#chapter-5-10 p > span{
		display: flex;
		justify-content: space-between;
		white-space: nowrap;
	}
}

#chapter-5-27 p > span,
#chapter-5-31 p > span,
#chapter-5-32 p > span{
	display: table;
	width: 100%;
}

#chapter-5-27 p > span > span,
#chapter-5-31 p > span > span,
#chapter-5-32 p > span > span{
	display: table-cell;
}

@supports(display: flex){
	#chapter-5-27 p > span,
	#chapter-5-31 p > span,
	#chapter-5-32 p > span{
		display: flex;
		justify-content: space-between;
		text-indent: 0;
	}
}

#chapter-5-37 p > span{
	display: inline-block;
	text-align: center;
	width: 5em;
}

#chapter-6-11 blockquote{
	margin: 0;
	text-align: center;
	text-indent: 0;
}

#chapter-6-14 p > span:first-of-type > span{
	display: inline-block;
	text-align: center;
	text-indent: 0;
	width: 5em;
}

@supports(display: flex){
	#chapter-6-14 p > span ~ span{
		display: flex;
		justify-content: space-between;
		text-indent: 0;
	}

	#chapter-6-14 p > span:last-child > span{
		flex-grow: 1;
	}

	#chapter-6-14 p > span:last-child > span:last-child{
		flex-grow: 4;
	}
}

@supports(display: flex){
	#chapter-6-17 p > span{
		display: flex;
		justify-content: space-between;
		text-indent: 0;
	}

	#chapter-6-17 p > span:last-child > span:last-child{
		margin-right: 42.25%;
	}
}

#chapter-6-19 ul{
	list-style-type: none;
	margin: 0 0 0 3em;
	padding: 0;
}

#chapter-6-19 ul + p{
	text-indent: 1em;
}

#chapter-6-19 div{
	display: flex;
}

#chapter-6-19 > div > p{
	white-space: nowrap;
}

#chapter-6-19 > div > ul{
	margin-left: .5em;
}

#chapter-6-19 > div:first-of-type li:last-child,
#chapter-6-19 > div:last-of-type li:last-child{
	margin-left: -2em;
}

#chapter-6-31 blockquote > p{
	text-align: center;
	text-indent: 0;
}

#chapter-6-34 p > span{
	display: inline-block;
	text-align: center;
	text-indent: 0;
	width: 3em;
}

#chapter-6-38 > div{
	break-after: page;
	break-before: page;
	min-height: 10em;
}

@supports(display: flex){
	#chapter-6-39 p > span{
		display: flex;
		justify-content: space-between;
		text-indent: 0;
	}

	#chapter-6-39 p > span:last-child > span:last-child{
		margin-right: 28.25%;
	}
}

#illustration-5{
	margin-left: 0;
	margin-right: 0;
}

@supports(display: flex){
	#chapter-7-8 p:nth-last-child(2){
		align-items: center;
		display: flex;
		text-indent: 0;
	}

	#chapter-7-8 p:nth-last-child(2) > span:nth-child(2){
		font-size: 2em;
		margin: 0 .25em;
	}

	#chapter-7-8 p:nth-last-child(2) > span:last-child > span{
		display: block;
	}

	#chapter-7-8 p:last-child{
		text-indent: 0;
	}
}

#chapter-7-10 tr:first-child{
	font-style: italic;
}

#chapter-7-18 [epub|type~="z3998:verse"] p,
#chapter-7-32 blockquote p,
#chapter-7-43 blockquote:first-of-type p{
	font-variant: all-small-caps;
	text-align: center;
}

#chapter-7-19 blockquote p{
	text-align: center;
	text-indent: 0;
}

#chapter-7-25 td[rowspan]{
	font-size: 3em;
	padding: 0 .25em;
}

#chapter-7-26 .followon::before{
	content: "⸺⁠Why, ’tis a strange story!";
	visibility: hidden;
}

#chapter-7-30 blockquote p{
	font-variant: small-caps;
	text-align: center;
}

@supports(display: flex){
	#chapter-7-31 p:nth-last-child(5){
		align-items: center;
		display: flex;
	}

	#chapter-7-31 p:nth-last-child(5) > span:first-child > span{
		display: block;
	}

	#chapter-7-31 p:nth-last-child(5) > span:nth-child(2){
		font-size: 2em;
		margin: 0 .25em;
	}
}

#chapter-7-35 blockquote > p{
	text-align: center;
	text-indent: 0;
}

#chapter-7-43 [epub|type~="z3998:song"]{
	font-variant: small-caps;
}

#chapter-8-11 .followon::before{
	content: "’Tis all ";
	visibility: hidden;
}

#chapter-8-13 p > span{
	display: inline-block;
	text-indent: 0;
	width: 1.2em;
}

#chapter-8-19 blockquote > p{
	font-variant: small-caps;
	text-align: center;
	text-indent: 0;
}

#chapter-8-20 p > span{
	display: inline-block;
	text-align: center;
	text-indent: 0;
	width: 2em;
}

#illustration-6 img{
	width: 80%;
}

#chapter-9-20 blockquote{
	font-style: italic;
	margin-bottom: 0;
	margin-top: 0;
}

#chapter-9-20 > p > span,
#chapter-9-22 > p > span,
#chapter-9-28 > p > span{
	display: inline-block;
	text-align: center;
	text-indent: 0;
	width: 3em;
}

"""

}
