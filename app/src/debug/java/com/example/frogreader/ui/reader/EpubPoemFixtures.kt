package com.example.frogreader.ui.reader

/** Public-domain Alice source fragments, preserving the publishers' XHTML and CSS. */
internal object EpubPoemFixtures {
    fun html(number: Int): String = if (number == 1) alice1Html else alice2Html
    fun css(number: Int): String = if (number == 1) alice1Css else alice2Css

    private val alice1Html = """
<html xmlns:epub="http://www.idpf.org/2007/ops"><body><section id="chapter-3"><p>Before the poem.</p>
<blockquote epub:type="z3998:poem">
				<p>
					<span>“Fury said to</span>
					<br/>
					<span class="i1">a mouse, That</span>
					<br/>
					<span class="i2">he met</span>
					<br/>
					<span class="i3">in the</span>
					<br/>
					<span class="i4">house,</span>
					<br/>
					<span class="i4">‘Let us</span>
					<br/>
					<span class="i3">both go</span>
					<br/>
					<span class="i3">to law:</span>
					<br/>
					<span class="i2"><em>I</em> will</span>
					<br/>
					<span class="i1">prosecute</span>
					<br/>
					<span><em>you</em>⁠—</span>
					<br/>
					<span>Come, I’ll</span>
					<br/>
					<span class="i1">take no</span>
					<br/>
					<span class="i2">denial;</span>
					<br/>
					<span class="i3">We must</span>
					<br/>
					<span class="i4">have a</span>
					<br/>
					<span class="i5">trial:</span>
					<br/>
					<span class="i6">For</span>
					<br/>
					<span class="i5">really</span>
					<br/>
					<span class="i4">this</span>
					<br/>
					<span class="i4">morning</span>
					<br/>
					<span class="i6">I’ve</span>
					<br/>
					<span class="i5">nothing</span>
					<br/>
					<span class="i4">to do.’</span>
					<br/>
					<span class="i3">Said the</span>
					<br/>
					<span class="i2">mouse to</span>
					<br/>
					<span class="i1">the cur,</span>
					<br/>
					<span>‘Such a</span>
					<br/>
					<span class="i3">trial,</span>
					<br/>
					<span class="i1">dear Sir,</span>
					<br/>
					<span>With no</span>
					<br/>
					<span class="i1">jury or</span>
					<br/>
					<span class="i2">judge,</span>
					<br/>
					<span class="i3">would be</span>
					<br/>
					<span class="i4">wasting</span>
					<br/>
					<span class="i3">our breath.’</span>
					<br/>
					<span class="i2">‘I’ll be</span>
					<br/>
					<span class="i3">judge,</span>
					<br/>
					<span class="i2">I’ll be</span>
					<br/>
					<span class="i1">jury,’</span>
					<br/>
					<span class="i1">Said</span>
					<br/>
					<span class="i2">cunning</span>
					<br/>
					<span class="i3">old Fury:</span>
					<br/>
					<span class="i4">‘I’ll try</span>
					<br/>
					<span class="i5">the whole</span>
					<br/>
					<span class="i6">cause,</span>
					<br/>
					<span class="i5">and</span>
					<br/>
					<span class="i4">condemn</span>
					<br/>
					<span class="i5">you</span>
					<br/>
					<span class="i6">to</span>
					<br/>
					<span class="i6">death.’ ”</span>
				</p>
			</blockquote>
<p>After the poem.</p></section></body></html>
"""

    private val alice1Css = """
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

@charset "utf-8";
@namespace epub "http://www.idpf.org/2007/ops";

[epub|type~="z3998:poem"] p,
[epub|type~="z3998:song"] p,
[epub|type~="z3998:verse"] p{
	text-align: initial;
	text-indent: 0;
}

[epub|type~="z3998:poem"] p + p,
[epub|type~="z3998:song"] p + p{
	margin-top: 1em;
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

p span.i1{
	padding-left: 2em;
	text-indent: -1em;
}

p span.i2{
	padding-left: 3em;
	text-indent: -1em;
}

p span.i3{
	padding-left: 4em;
	text-indent: -1em;
}

p span.i4{
	padding-left: 5em;
	text-indent: -1em;
}

p span.i5{
	padding-left: 6em;
	text-indent: -1em;
}

p span.i6{
	padding-left: 7em;
	text-indent: -1em;
}

#chapter-2 [epub|type~="z3998:verse"]{
	font-variant: small-caps;
}

#chapter-3 [epub|type~="z3998:poem"]{
	text-align: center;
}

#chapter-3 [epub|type~="z3998:poem"] p{
	display: inline-block;
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

figure.half-page img{
	max-width: 50%;
}

/* All epigraphs */
[epub|type~="epigraph"]{
	font-style: italic;
	hyphens: none;
	-epub-hyphens: none;
}

[epub|type~="epigraph"] em{
	font-style: normal;
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

"""

    private val alice2Html = """
<html><body><p>Before the poem.</p>
<div class="poem stanza"><span class="i1">"Fury said to<br/></span> <span class="i2">a mouse, That<br/></span> <span class="i3">he met in the<br/></span> <span class="i4">house, 'Let<br/></span> <span class="i5">us both go<br/></span> <span class="i6">to law: <i>I</i><br/></span> <span class="i6 c9">will prosecute<br/></span> <span class="i6 c9"><i>you</i>.—<br/></span> <span class="i6 c9">Come, I'll<br/></span> <span class="i5 c9">take no denial:<br/></span> <span class="c20"><span class="i4 c9">We must have<br/></span> <span class="i3 c10">the trial;<br/></span> <span class="i2 c10">For really<br/></span> <span class="i1 c10">this morning<br/></span> <span class="i0 c10">I've<br/></span> <span class="i0 c10">nothing<br/></span> <span class="i0 c11">to do.'<br/></span> <span class="i1 c11">Said the<br/></span> <span class="i2 c11">mouse to<br/></span> <span class="c19"><span class="i3 c11">the cur,<br/></span> <span class="i4 c11">'Such a<br/></span> <span class="i5 c12">trial, dear<br/></span> <span class="i6 c12">sir, With<br/></span> <span class="i8 c12">no jury<br/></span> <span class="i9 c12">or judge,<br/></span> <span class="i9 c12">would<br/></span> <span class="i8 c13">be wasting<br/></span> <span class="i7 c13">our<br/></span> <span class="c18"><span class="i5 c13">breath.'<br/></span> <span class="i4 c13">'I'll be<br/></span> <span class="i3 c13">judge,<br/></span> <span class="i2 c14">I'll be<br/></span> <span class="i1 c14">jury,'<br/></span> <span class="i0 c14">said<br/></span> <span class="i0 c14">cunning<br/></span> <span class="i1 c14">old<br/></span> <span class="i2 c15">Fury;<br/></span> <span class="c17"><span class="i3 c15">'I'll<br/></span> <span class="i4 c15">try<br/></span> <span class="i5 c15">the<br/></span> <span class="i6 c15">whole<br/></span> <span class="i7 c16">cause,<br/></span> <span class="i7 c16">and<br/></span> <span class="i6 c16">condemn<br/></span> <span class="i5 c16">you to<br/></span> <span class="i3 c16">death.'"<br/></span></span></span></span></span></div>
<p>After the poem.</p></body></html>
"""

    private val alice2Css = """
/*  XML blockout */
p {
    margin-top: 0.75em;
    margin-bottom: 0.75em
    }
h1, h2, h4, h5, h6 {
    text-align: center;
    /* all headings centered */
    clear: both
    }
h3 {
    text-decoration: underline;
    text-align: center;
    clear: both
    }
hr {
    width: 33%;
    margin-top: 2em;
    margin-bottom: 2em;
    margin-left: auto;
    margin-right: auto;
    clear: both
    }
body {
    margin-left: 10%;
    margin-right: 10%
    }
img {
    border-style: none
    }
.pagenum {
    /* uncomment the next line for invisible page numbers */
    /*  visibility: hidden;  */
    position: absolute;
    left: 92%;
    font-size: smaller;
    text-align: right
    }
/* page numbers */
.center {
    text-align: center
    }
.caption {
    font-weight: bold;
    text-align: center;
    margin-bottom: 2em
    }
.figcenter {
    margin: auto;
    text-align: center;
    margin-top: 2em
    }
.figleft {
    float: left;
    clear: left;
    margin-left: 0;
    margin-bottom: 0;
    margin-top: 0;
    margin-right: 0;
    padding: 0;
    text-align: center
    }
.figright {
    float: right;
    clear: right;
    margin-left: 0;
    margin-bottom: 0;
    margin-top: 0;
    margin-right: 0;
    padding: 0;
    text-align: center
    }
.poem {
    margin-left: 10%;
    margin-right: 10%;
    text-align: left
    }
.poem br {
    display: none
    }
.poem .stanza {
    margin: 1em 0 1em 0
    }
.poem span.i0 {
    display: block;
    margin-left: 0;
    padding-left: 3em;
    text-indent: -3em
    }
.poem span.i2 {
    display: block;
    margin-left: 2em;
    padding-left: 3em;
    text-indent: -3em
    }
.poem span.i4 {
    display: block;
    margin-left: 4em;
    padding-left: 3em;
    text-indent: -3em
    }
.poem span.i1 {
    display: block;
    margin-left: 1em;
    padding-left: 3em;
    text-indent: -3em
    }
.poem span.i10 {
    display: block;
    margin-left: 10em;
    padding-left: 3em;
    text-indent: -3em
    }
.poem span.i11 {
    display: block;
    margin-left: 11em;
    padding-left: 3em;
    text-indent: -3em
    }
.poem span.i12 {
    display: block;
    margin-left: 12em;
    padding-left: 3em;
    text-indent: -3em
    }
.poem span.i13 {
    display: block;
    margin-left: 13em;
    padding-left: 3em;
    text-indent: -3em
    }
.poem span.i14 {
    display: block;
    margin-left: 14em;
    padding-left: 3em;
    text-indent: -3em
    }
.poem span.i15 {
    display: block;
    margin-left: 15em;
    padding-left: 3em;
    text-indent: -3em
    }
.poem span.i3 {
    display: block;
    margin-left: 3em;
    padding-left: 3em;
    text-indent: -3em
    }
.poem span.i5 {
    display: block;
    margin-left: 5em;
    padding-left: 3em;
    text-indent: -3em
    }
.poem span.i6 {
    display: block;
    margin-left: 6em;
    padding-left: 3em;
    text-indent: -3em
    }
.poem span.i7 {
    display: block;
    margin-left: 7em;
    padding-left: 3em;
    text-indent: -3em
    }
.poem span.i8 {
    display: block;
    margin-left: 8em;
    padding-left: 3em;
    text-indent: -3em
    }
.poem span.i9 {
    display: block;
    margin-left: 9em;
    padding-left: 3em;
    text-indent: -3em
    }

    /**/
span.c20 {
    line-height: 0.9em
    }
span.c19 {
    line-height: 0.8em
    }
span.c18 {
    line-height: 0.7em
    }
span.c17 {
    line-height: 0.6em
    }
span.c16 {
    font-size: 60%
    }
span.c15 {
    font-size: 65%
    }
span.c14 {
    font-size: 70%
    }
span.c13 {
    font-size: 75%
    }
span.c12 {
    font-size: 80%
    }
span.c11 {
    font-size: 85%
    }
span.c10 {
    font-size: 90%
    }
span.c9 {
    font-size: 95%
    }
hr.c8 {
    width: 45%
    }
span.c7 {
float: left;
font-size: 3.2em;
margin-right: 8pt
    font-weight: bold
    }
span.c72 {
float: left;
font-size: 3em;
margin-right: 8pt
    font-weight: bold
    }
p.c6 {
    font-size: 150%;
    margin-top: 4em;
    margin-left: 13em;
    font-weight: bold
    }
p.c5 {
    margin-right: 4em;
    margin-top: 4em;
    margin-bottom: 2em
    }
h4.c4 {
    padding-top: 2em;
    padding-bottom: 2em
    }
div.c3 {
    width: 66px;
    padding-top: 4em
    }
h1.c2 {
    padding-top: 2em;
    padding-bottom: 1em
    }
h3.c1 {
    font-style: italic
    }
/**/
"""
}
