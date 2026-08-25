# Аудит движка форматов FrogReader

Дата среза: 2026-08-25. Область: FB2 2.x, EPUB 2 / EPUB 3.3,
legacy MOBI6/7 и Kindle Format 8 (KF8/AZW3). Аудио и видео EPUB/KF8
намеренно отложены по решению владельца продукта.

## Что именно считается четырьмя форматами

Расширение файла не всегда называет внутренний формат. `.mobi` может содержать
только legacy MOBI6/7 или быть combo-контейнером с MOBI6 и KF8; `.azw3` обычно
содержит только KF8. FrogReader поэтому должен определять секцию по заголовкам
PDB/MOBI, а не выбирать движок только по расширению.

В этом аудите четыре независимых пути проверки:

1. FB2 2.x — XML-семантика FictionBook.
2. EPUB 2/3 — OPF + XHTML/SVG + CSS.
3. MOBI6/7 — PalmDOC/PDB + legacy HTML/filepos.
4. KF8/AZW3 — PDB/KF8 + HTML5/CSS/FDST/INDX.

KFX не является синонимом KF8. Это отдельный современный закрытый формат
Amazon; текущий импорт FrogReader его не заявляет и этот аудит не засчитывает
его как «четвёртый» формат.

## Методика и критерий паритета

Проверка идёт в пять слоёв: нормативные возможности формата, распаковка и
структура пакета, преобразование в общую модель `BookContent`, измерение и
рисование Jetpack Compose, затем ручная проверка на реальном устройстве.
Зелёный parser test не считается доказательством, если общая модель или
renderer затем теряет результат; наличие decoder dependency не считается
доказательством реальной анимации на телефоне.

Уровни доказательства в этом документе:

1. **Spec** — возможность подтверждена нормативной документацией формата.
2. **Parser/model** — fixture доказывает, что смысл и данные дошли до
   `BookContent` без ложной нормализации.
3. **Measure/render** — тест доказывает, что пагинация и отрисовка используют
   одинаковые метрики и не теряют сохранённое оформление.
4. **Device** — реальная книга вручную проверена владельцем на Pixel 9a.

Матрица ниже отражает реализованный код и автоматические доказательства.
Возможности, которым ещё нужен device-gate (в первую очередь GIF, сложные SVG,
embedded fonts и очень широкие таблицы), явно не выдаются за полностью
подтверждённые визуально.

Для каждой возможности используется одна классификация:

- ✅ — формат умеет, текущий путь FrogReader сохраняет и рисует;
- ◐ — формат умеет, поддержка частичная или есть намеренная деградация;
- ❌ — формат умеет, читалка пока не поддерживает;
- Ø — формат так не умеет или элемент не существует в его стандарте;
- ⚠ — зависит от недокументированной/вариативной реализации и требует
  отдельной реальной книги.

«Паритет» не означает выдумывать CSS для FB2 или MathML для MOBI6. Цель — одна
авторская книга, корректно экспортированная во все четыре формата, должна
сохранить одинаковые смысловые блоки, порядок, навигацию, изображения и
максимально близкую типографику в пределах возможностей каждого формата.

## Архитектурный вывод

Сейчас FrogReader — нормализующий reflow-движок: все форматы превращаются в
небольшой плоский набор paragraph/heading/image/table/spacer. Это сильная
архитектура для обычной прозы: единая пагинация, поиск, выделение, темы и
закладки. Но она принципиально не может выразить полный CSS box model,
fixed-layout, полноценную двумерную MathML-вёрстку, вертикальное письмо,
абсолютное позиционирование и сложные SVG/HTML accessibility trees. Для MathML
теперь есть bounded native fallback, который сохраняет формулу читаемой и
стилизованной, но не притворяется браузерной математической версткой.

Поэтому путь к действительно лучшему движку двухконтурный:

1. Сохранить Compose-reflow как основной быстрый и удобный путь для прозы.
2. Добавить publisher-layout surface для fixed-layout, полной MathML fidelity и
   документов, которые требуют web/SVG layout; выбор должен делаться по package
   metadata и фактически используемым возможностям, а не по расширению файла.

Без второго контура обещание полного EPUB/KF8-паритета было бы технически
нечестным. Исправления этого прохода максимально расширяют безопасную общую
часть и явно фиксируют оставшуюся границу.

## Исправления, внесённые по результатам аудита

- Заголовок теперь хранит `AnnotatedString`: strong/emphasis, ссылки,
  superscript/subscript и inline-изображения больше не уплощаются в строку.
- Пагинация и renderer измеряют и рисуют rich heading с теми же placeholders,
  что и абзацы.
- H1–H6 имеют шесть разных размеров в общем renderer; FB2 structural depth,
  EPUB/KF8 `<h1>`…`<h6>` и legacy MOBI проходят один и тот же metric path.
- Обычные fragment-ссылки отделены от настоящих `noteref`; popup-сноски больше
  не поглощают содержание и перекрёстные ссылки.
- Inline `<img>` остаётся в исходной позиции текста; крупные и float-images
  остаются блочными/обтекаемыми без дублирования.
- Embedded SVG сохраняется целиком, включая одновременные vector shapes, text
  и raster `<image>`; локальный raster в сериализованном SVG встраивается как
  data URI.
- Standalone SVG в EPUB spine открывается как полноценная страница-картинка.
- `<pre>` сохраняет пробелы и переводы строк и использует monospace.
- GIF decoder подключён для API 26+ и parser сохраняет GIF как GIF; реальное
  воспроизведение анимации остаётся обязательным ручным device-gate.
- `alt`, `aria-label` и `title` доходят до block-image accessibility; при
  отсутствующем ресурсе текстовый fallback остаётся видимым.
- CSS `@import` в EPUB разрешается рекурсивно относительно импортирующего
  файла, с защитой от циклов.
- KF8 `kindle:flow` `@import` обрабатывается отдельно от MOBI6, с Kindle media
  queries, source order, повторными импортами, защитой от циклов и враждебных
  графов ресурсов.
- Inline `style=` работает даже в XHTML/KF8-документе без отдельного
  `<link rel="stylesheet">` или `<style>` блока.
- Явный короткий leading text span с `float:left/right` преобразуется в тот же
  SideBox, что и `::first-letter`: EPUB/MOBI6/KF8 сохраняют точный glyph,
  сторону, scale, font, color, language/direction и не дублируют букву. Это
  даёт KF8 переносимую буквицу без неподдерживаемого им `::first-letter`.
- CSS media types теперь различают screen/print/speech, `not screen`,
  `not print` и comma alternatives; print-правила не протекают в reader.
- EPUB проходит manifest fallback chain, уважает объявленный `spine@toc` NCX,
  tokenized `properties`, literal `+` в ZIP path и standalone SVG spine.
- Несколько EPUB nav/NCX targets внутри одного XHTML становятся отдельными
  главами; percent-encoded Unicode fragments разрешаются в реальные XML ids.
- EPUB `spine itemref linear="no"` хранится вне reading order и открывается
  только через typed hyperlink/TOC target в отдельной transient surface;
  обычный прогресс, поиск, пагинация и завершение книги не меняются.
- Legacy FB2EPUB notes распознаются только по совокупности converter metadata,
  bracketed marker, `ch2-N.xhtml` и fragment — без глобальной ложной эвристики.
- FB2 исправляет first-body semantics, полный poem/stanza/subtitle/date,
  rich titles, inline images в стихах/таблицах, SVG/GIF MIME extraction,
  image alt fallback, обычные anchors и разделение link/noteref.
- FB2 root `text/css` stylesheets, literal `style=` и named `<style name>`
  проходят ограниченный XML-aware cascade; `xml:lang` наследуется от body и
  section до блоков и отдельных inline runs.
- Reflow bidi теперь проходит единый путь для FB2/EPUB/MOBI6/KF8: наследуемые
  HTML `dir=ltr/rtl/auto`, CSS `direction`/`unicode-bidi`, `bdi`/`bdo`,
  заголовки, списки и ячейки таблиц доходят до Compose layout. Служебные UBA
  controls создаются только во временной layout-строке с явной картой offsets;
  исходный текст поиска, копирования, ссылок и сносок остаётся неизменным.
  Логические `start/end` и физические `left/right` не смешиваются; их cascade
  не удваивает один и тот же отступ. Горизонтальная геометрия книги не зависит
  от языка Android UI, а первая авторская колонка RTL-таблицы рисуется справа.
- MOBI6 и KF8 отдельно фильтруют `amzn-mobi`/`amzn-kf8`, сохраняют legacy CSS,
  NCX/INDX navigation и разделяют filepos navigation от реальных note markers.
- Legacy HTML `align=` и `<center>` наследуются блоками; CSS остаётся сильнее.
- Legacy `<font size/face>` больше не теряет размер и generic font family.
- Typography таблицы (scale/family/line-height/bold/italic/lang/direction)
  теперь участвует и в измерении, и в drawing, а не только в outer margins.
- Inline-изображение в ячейке участвует в min/max intrinsic measurement и
  рисуется тем же rich-text path; широкая картинка больше не сжимается до ширины
  символа U+FFFC и не обрезается распределителем колонок.
- Семантические сноски теперь хранят полный `NoteDocument`, а не первые три
  абзаца/700 символов: popup использует общий renderer для заголовков, списков,
  цитат, стихов, таблиц, block/inline images, CSS и ссылок note-to-note.
- EPUB 2 DTBook (`application/x-dtbook+xml`) проходит реальный
  manifest/spine/NCX path: levels, lists, poems, tables, SVG, anchors и CSS не
  требуют ложного XHTML media type.
- Presentation MathML имеет ограниченный native formatter для scripts,
  fractions, roots, fences, limits, matrices, semantics/accessibility fallback,
  anchors и links; inline/display placement сохраняется.
- CSS cascade теперь корректно разрешает margin/font shorthands, relative
  line-height, nested imports/media, selector work limits и повторные aliases;
  FB2 compatibility profile использует те же ключевые правила каскада.
- На API 26–32 sniffing книги больше не вызывает API 33-only
  `InputStream.readNBytes`; добавлен совместимый bounded prefix reader.
- Введены publication-wide budgets для ZIP/FB2/PDB records, decompression,
  CSS/import expansion, DOM/generated content, fonts/WOFF, HUFF dictionaries,
  KF8 assembly/index/markers и SVG resources. Повреждённый декоративный ресурс
  деградирует локально, а обязательный content даёт контролируемую ошибку.
- CSS `color`/`background-color`, legacy `color`/`text`/`bgcolor` и FB2 styles
  сохраняются в блоках, inline runs, headings, first-letter, tables/cells и
  rich notes. Publisher toggle полностью снимает их; одиночная авторская
  сторона получает contrast-safe пару, полная foreground/background пара
  сохраняется без самовольной перекраски.

## Единый нумерованный capability checklist

Одинаковые номера намеренно применяются ко всем четырём колонкам. Так любой
регрессионный corpus и ручная книга могут ссылаться на один case ID независимо
от контейнера.

### A. Контейнер, metadata и ресурсы

| № | Возможность | FB2 | EPUB | MOBI6 | KF8/AZW3 |
|---:|---|:---:|:---:|:---:|:---:|
| 1 | Сигнатура/контейнер определяется по содержимому | ✅ | ✅ | ✅ | ✅ |
| 2 | Корректная кодировка текста | ✅ | ✅ | ✅ | ✅ |
| 3 | Сжатие и безопасные границы ресурсов | ✅ | ✅ | ✅ | ✅ |
| 4 | Шифрование/DRM | Ø | ◐ | 🔒 | 🔒 |
| 5 | Title и основной author | ✅ | ✅ | ✅ | ✅ |
| 6 | Несколько authors/contributors/translators | ✅ | ◐ | ◐ | ◐ |
| 7 | Publisher, date/year, ISBN, subjects/genres | ✅ | ✅ | ✅ | ✅ |
| 8 | Series/collection | ✅ | ✅ | Ø | Ø |
| 9 | Annotation/description | ◐ | ◐ | ◐ | ◐ |
| 10 | Cover и thumbnail | ◐ | ✅ | ◐ | ◐ |
| 11 | Keywords, provenance, rights, roles, refinements | ◐ | ◐ | ◐ | ◐ |
| 12 | Global publication language | ✅ | ✅ | ✅ | ✅ |
| 13 | Несколько renditions одной публикации | Ø | ❌ | Ø | Ø |
| 14 | Manifest/resource fallback chain | Ø | ✅ | Ø | ⚠ |
| 15 | Встроенные/обфусцированные fonts | Ø | ✅ | Ø | ✅ |

`🔒` означает сознательный отказ от DRM, а не попытку обойти защиту. EPUB
понимает стандартное IDPF/Adobe font obfuscation, но не коммерческое DRM.

### B. Порядок, главы, навигация и ссылки

| № | Возможность | FB2 | EPUB | MOBI6 | KF8/AZW3 |
|---:|---|:---:|:---:|:---:|:---:|
| 16 | Авторский основной reading order | ✅ | ✅ | ✅ | ✅ |
| 17 | Дополнительные/non-linear bodies/resources | ◐ | ✅ | Ø | ◐ |
| 18 | Вложенная иерархия section/chapter | ✅ | ✅ | ✅ | ✅ |
| 19 | TOC/NCX/INDX labels и depth | ✅ | ✅ | ✅ | ✅ |
| 20 | Несколько TOC targets в одном content file | ✅ | ✅ | ✅ | ◐ |
| 21 | Page-list, landmarks, guide/start-reading | Ø | ❌ | ❌ | ❌ |
| 22 | IDs/anchors на обычных блоках | ◐ | ✅ | ✅ | ✅ |
| 23 | Обычные внутренние cross-references | ✅ | ✅ | ✅ | ✅ |
| 24 | Семантические footnotes/endnotes | ✅ | ✅ | ✅ | ✅ |
| 25 | Backlink остаётся навигацией, а не popup-note | ✅ | ✅ | ✅ | ✅ |
| 26 | Безопасные HTTP(S)/mailto/tel links | ✅ | ✅ | ✅ | ✅ |
| 27 | EPUB CFI / Kindle locations / переносимые ranges | Ø | ❌ | ❌ | ❌ |
| 28 | Author page breaks before block | ◐ | ✅ | ✅ | ✅ |
| 29 | Break after/inside, widows/orphans | Ø/◐ | ❌ | ❌ | ❌ |
| 30 | RTL page progression / spreads | ◐ | ◐ | ◐ | ◐ |

EPUB `linear="no"` не подмешивается в главы и прогресс: обычная XHTML-ссылка
или nav/NCX row открывает документ отдельно, а Back возвращает на прежнее
место. Popup-note хранит `NoteDocument` и использует общий `RenderPart`, поэтому
таблицы, изображения, списки, стихи, заголовки и ссылки не уплощаются.

Reflow reader зеркалит физический порядок страниц, зоны касания и selection
auto-turn для RTL. EPUB `page-progression-direction` имеет приоритет; FB2,
MOBI6 и KF8 без сохранённой явной директивы безопасно выводят направление из
языка книги. Авторские spreads пока относятся к отложенному fixed-layout слою,
поэтому строка 30 остаётся частичной, а не полной поддержкой.

Все форматы используют одну browser-like историю внутренних переходов: ссылки,
TOC, поиск, закладки, цитаты, progress scrub и случайный scroll-прыжок более чем
на два экрана сохраняют исходную позицию. Контекстная кнопка физически находится
справа, учитывает реальную высоту/анимацию нижней панели, исчезает через короткий
интервал, но системный Back остаётся доступен ограниченное время. Позиции main,
EPUB `linear=no` и rich-note хранятся раздельно; в scroll mode текстовый anchor
восстанавливается после изменения ширины или шрифта, а non-text блоки имеют
pixel fallback.

### C. Текстовая структура и inline formatting

| № | Возможность | FB2 | EPUB | MOBI6 | KF8/AZW3 |
|---:|---|:---:|:---:|:---:|:---:|
| 31 | Paragraphs и mixed inline content | ✅ | ✅ | ✅ | ✅ |
| 32 | Rich multi-line headings/titles | ✅ | ✅ | ✅ | ✅ |
| 33 | Subtitle | ✅ | ◐ | ◐ | ◐ |
| 34 | Bold/strong и italic/emphasis | ✅ | ✅ | ✅ | ✅ |
| 35 | Underline, strike/del/ins | ◐ | ✅ | ✅ | ✅ |
| 36 | Superscript/subscript | ✅ | ✅ | ✅ | ✅ |
| 37 | Code/monospace и preformatted whitespace | ✅/Ø | ✅ | ✅ | ✅ |
| 38 | Quote/blockquote/cite/epigraph | ✅ | ✅ | ✅ | ✅ |
| 39 | Poem, stanza, verse, text-author, date | ✅ | ◐ | Ø/◐ | ◐ |
| 40 | Ordered/unordered/nested lists | Ø | ✅ | ✅ | ✅ |
| 41 | Definition lists | Ø | ◐ | ◐ | ◐ |
| 42 | Ruby | Ø | ◐ | Ø | ◐ |
| 43 | `<q>` с языковыми кавычками | Ø | ✅ | Ø/⚠ | ✅ |
| 44 | `<mark>` как видимое выделение | Ø | ◐ | Ø/⚠ | ◐ |
| 45 | `<wbr>` break opportunity | Ø | ✅ | Ø | ✅ |
| 46 | `<nobr>` / CSS white-space modes | Ø | ❌ | ◐ | ❌ |
| 47 | Generated `::before`/`::after` strings | Ø | ◐ | ⚠ | Ø/⚠ |
| 48 | Drop caps: `::first-letter` / explicit floated span | Ø | ◐ | ◐ | ◐ |
| 49 | Block `lang`/`xml:lang` | ✅ | ✅ | ◐ | ✅ |
| 50 | Inline span language | ✅ | ✅ | ◐ | ✅ |
| 51 | `dir`/CSS `direction` на блоке | Ø/◐ | ✅ | ◐ | ✅ |
| 52 | `bdi`/`bdo`/`unicode-bidi` | Ø/◐ | ✅ | ◐ | ✅ |
| 53 | Vertical writing/text orientation/combine | Ø | ❌ | Ø | ⚠ |

Ruby сейчас является понятной деградацией «base + маленький superscript rt»,
но не настоящим межстрочным ruby layout. Bidi isolation/override работает в
native reflow, а вертикальное CJK по-прежнему требует fidelity renderer.

FB2 2.x не имеет стандартизованных `dir`, `bdi` или `unicode-bidi`: FrogReader
может вывести направление блока из `xml:lang` и изолировать явно помеченный
другим языком inline run, но не способен восстановить невыраженный авторский
override. Legacy MOBI6 также не даёт надёжного контракта для современных
HTML5-isolates: базовые `dir`/CSS и сохранённая разметка обрабатываются, если
они реально дошли до контента. EPUB и KF8 выражают эти семантики полнее.
В пределах одного flattened native paragraph CSS `unicode-bidi: plaintext`
использует безопасный first-strong isolate; отдельный reset на каждой внутренней
CSS paragraph boundary невозможен, если исходный box уже был уплощён моделью.
Автотесты доказывают parser/model/offset/measure поведение; shaping сложных
арабских лигатур и mixed selection ещё требует ручного device-gate на Pixel 9a.

### D. CSS и геометрия reflowable layout

| № | Возможность | FB2 | EPUB | MOBI6 | KF8/AZW3 |
|---:|---|:---:|:---:|:---:|:---:|
| 54 | Stylesheet и inline `style` | ◐ | ✅ | ◐ | ✅ |
| 55 | Cascade/specificity/inheritance/`!important` | ◐ | ◐ | ◐ | ◐ |
| 56 | Class/id/tag/attribute/combinator selectors | ◐ | ◐ | ◐ | ◐ |
| 57 | Structural pseudo-classes | Ø | ◐ | ⚠ | Ø/⚠ |
| 58 | CSS custom properties и `calc()` subset | Ø/⚠ | ◐ | ⚠ | ⚠ |
| 59 | Local recursive `@import` | Ø/⚠ | ✅ | Ø/⚠ | ✅ |
| 60 | Screen/print и Kindle media types | Ø/◐ | ◐ | ✅ | ✅ |
| 61 | Device width/aspect/orientation queries | Ø | ❌ | Ø/⚠ | ❌ |
| 62 | Font family/style/weight/size | ◐ | ◐ | ◐ | ◐ |
| 63 | Inline named embedded font family | Ø | ◐ | Ø | ◐ |
| 64 | Line-height и hyphenation hints | ◐ | ◐ | ◐ | ◐ |
| 65 | Text align/justify/indent | ✅ | ✅ | ✅ | ✅ |
| 66 | Margins/padding/centered boxes | ◐ | ◐ | ◐ | ◐ |
| 67 | Foreground color и background | ✅ | ✅ | ✅ | ✅ |
| 68 | Borders/radius/outline/shadow | ❌ | ❌ | ❌ | ❌ |
| 69 | Letter/word spacing, transform, text-shadow | ❌ | ❌ | ❌ | ❌ |
| 70 | Image float и basic text wrapping | Ø/◐ | ✅ | ✅ | ✅ |
| 71 | Clear/overflow/object-fit/min-max/aspect-ratio | Ø | ❌ | ❌ | ❌ |
| 72 | Absolute/fixed positioning, z-index, transform | Ø | ❌ | Ø | ❌ |
| 73 | Flex/grid/columns | Ø | ❌ | Ø | Ø/⚠ |
| 74 | Full table CSS/border-collapse/layout | ◐ | ❌ | ❌ | ❌ |
| 75 | `@page`, named pages, page floats | Ø | ❌ | Ø | Ø/⚠ |

FB2 stylesheet support — это compatibility-профиль поверх семантического XML,
а не браузерная обязанность. EPUB/KF8, напротив, реально умеют гораздо больше
CSS, поэтому строки 67–75 являются подтверждёнными reader gaps, а не пределом
форматов.

### E. Изображения, SVG, таблицы и специальные режимы

| № | Возможность | FB2 | EPUB | MOBI6 | KF8/AZW3 |
|---:|---|:---:|:---:|:---:|:---:|
| 76 | JPEG/PNG и block images | ✅ | ✅ | ✅ | ✅ |
| 77 | GIF animation | ◐ | ◐ | ⚠ | ⚠ |
| 78 | WebP/BMP/AVIF tolerant static decode | ◐ | ◐ | ⚠ | ⚠ |
| 79 | Inline images в исходной позиции | ✅ | ✅ | ✅ | ✅ |
| 80 | Float images без дублирования | Ø/◐ | ✅ | ✅ | ✅ |
| 81 | Width/height/aspect preservation | ◐ | ◐ | ✅ | ✅ |
| 82 | `alt`/`aria-label`/`title`, missing fallback | ◐ | ◐ | ◐ | ◐ |
| 83 | SVG binary/by-reference | ✅ | ◐ | Ø/⚠ | ◐ |
| 84 | Mixed inline SVG shapes + text + raster image | Ø | ◐ | Ø | ◐ |
| 85 | Standalone SVG content page | Ø | ◐ | Ø | ❌ |
| 86 | SVG links/search/CSS/fonts/resource origin | Ø | ❌ | Ø | ❌ |
| 87 | Table grid/header/caption | ◐ | ✅ | ✅ | ✅ |
| 88 | Colspan/rowspan | ◐ | ◐ | ◐ | ◐ |
| 89 | Cell align | ✅ | ✅ | ✅ | ✅ |
| 90 | Cell vertical align/colgroup/complex nested cells | ❌ | ❌ | ❌ | ❌ |
| 91 | Table splitting и repeated header | ✅ | ✅ | ✅ | ✅ |
| 92 | Presentation MathML | Ø | ◐ | Ø | Ø |
| 93 | Fixed-layout pages/spreads/orientation | Ø | ❌ | Ø | ❌ |
| 94 | KF8 panels/region magnification/text popups | Ø | Ø | Ø | ❌ |
| 95 | Script/forms/canvas/iframe | Ø | ❌/optional | Ø | Ø |
| 96 | Audio/video/media overlays | Ø | ⏸ | Ø | ⏸/Ø |
| 97 | Semantic accessibility tree/ARIA/DPUB-ARIA | ◐ | ◐ | Ø/◐ | ◐ |
| 98 | Search/selection по normal text | ✅ | ✅ | ✅ | ✅ |
| 99 | Search/selection по SVG/MathML/nonlinear content | Ø | ◐ | Ø | ❌ |
| 100 | Large-file bounds, damaged input degradation | ◐ | ◐ | ◐ | ◐ |

`⏸` — явно отложенные audio/video. Для KF8 официальная support table также
помечает обычные HTML audio/video как неподдерживаемые, несмотря на отдельные
исторические Kindle publishing workflows.

## Профиль каждого движка

### FB2 2.x

FB2 прежде всего описывает смысл книги XML-элементами: body/section/title,
epigraph/cite, poem/stanza/v, annotation, дополнительными bodies для сносок,
базовыми таблицами и binary-ресурсами. XSD разрешает произвольные root
`stylesheet`, literal `style` на текстовых/табличных элементах, named inline
`<style name>` и `xml:lang`, но не определяет обязательный браузерный layout
engine. Поэтому отсутствие flex/grid не является дефектом FB2-reader, а потеря
разрешённых `style`, таблицы, языка или rich title — является.

Текущий native path сохраняет всю основную структуру, глубокие section levels
до H6, rich titles/subtitles, стихи, таблицы, ссылки/сноски, inline/block images,
SVG/GIF binary, полный rich note body и ограниченный CSS compatibility profile.
Оставшаяся крупная граница — элементы, которых в самой FB2-модели нет
(настоящие HTML lists, MathML, fixed layout, сложный SVG DOM).

### EPUB 2 / EPUB 3.3

EPUB — самый широкий из четырёх форматов: пакет может включать XHTML, CSS, SVG,
Presentation MathML, embedded fonts, reflow/fixed-layout metadata, nav/NCX,
page-list/landmarks, non-linear spine resources, fallbacks, media overlays и
скрипты. Поэтому «HTML-текст открылся» — лишь базовый уровень EPUB support.

Reflow path сейчас сохраняет package/spine/fallbacks, nav и NCX с несколькими
fragment targets в одном XHTML, Unicode IRI fragments, embedded/obfuscated
fonts, recursive imports, substantial CSS cascade, tables, lists, ruby,
preformatted text, SVG/images, semantic links и отдельно открываемые
`linear="no"` documents. EPUB 2 DTBook проходит тот же package path, а
Presentation MathML имеет читаемый native fallback. Главные настоящие reader
gaps: fixed layout и browser-level MathML fidelity, vertical writing, полный
CSS painting/box model, SVG как searchable/accessibility tree,
scripts/media overlays и DPUB-ARIA semantics.

### MOBI6/7

Legacy MOBI — PDB/PalmDOC container с OEB-подобным HTML, record/filepos links,
EXTH metadata, старым presentational markup и существенно меньшим CSS/HTML
пространством. Нельзя требовать от него EPUB 3 MathML, modern SVG DOM, grid или
fixed-layout semantics, которых формат не выражает надёжно.

Текущий путь отдельно декодирует PalmDOC/HUFF-CDIC text, charset/EXTH,
filepos/guide/NCX-like navigation, legacy `<font>`, `align`, tables, images,
pagebreaks, ссылки и узко распознаваемые note markers. Его приоритет — не
«эмулировать браузер», а не терять редкую старую авторскую разметку и безопасно
деградировать повреждённые записи. `.mobi`, `.prc` и старый `.azw` остаются
одним legacy engine независимо от расширения.

### KF8 / MOBI8 / AZW3

KF8 несёт HTML5/CSS и ресурсы в PDB/KF8 structures (FDST flows,
skeleton/fragment reconstruction, INDX/NCX). Это отдельная capability column,
даже когда KF8 находится второй секцией внутри combo `.mobi`. Pure `.azw3` и
combo KF8 должны давать одинаковый reflow result.

Текущий путь реконструирует KF8 markup, resources/fonts/navigation, различает
`amzn-kf8` и `amzn-mobi`, рекурсивно и итеративно раскрывает `kindle:flow`
`@import`, сохраняет repeat source order и ограничивает враждебный import graph.
Официальная Amazon-таблица при этом прямо помечает `::before`, `::after`,
`::first-letter` и structural pseudo-classes как неподдерживаемые KF8: их
отсутствие нельзя записывать как нормативный reader gap. Реальные крупные gaps
— KF8 fixed-layout/panels/region magnification/text popups, полный поддержанный
Amazon CSS painting. KFX остаётся отдельным пятым закрытым
контейнером и в эту реализацию не входит.

## Оставшаяся работа по приоритету

### P0 — отдельная архитектура, а не ещё один parser `when`

1. **Publisher-layout surface.** Ввести типизированный выбор surface:
   `NativeReflow` для прозы и изолированный package-aware layout для EPUB
   fixed-layout, полной Presentation MathML, vertical writing, full SVG/HTML и KF8
   fixed panels. Он должен получать локальные ресурсы через контролируемый
   origin, запрещать произвольный network/file access, сохранять typed internal
   navigation и не смешивать координаты с reflow progress.
2. **Два пространства прогресса.** Основной reading order уже отделён от EPUB
   linked documents. Publisher-layout должен продолжить этот инвариант и иметь
   стабильные anchors без фиктивных chapter indices.

Rich notes и publication-wide resource budgets из прежнего P0 реализованы и
закрыты автоматическими регрессиями. Они больше не перечисляются как будущая
архитектура.

### Publisher-layout: принятый архитектурный план

Fixed-layout нельзя добавлять как ещё один `ContentElement`: один EPUB spine
может смешивать reflowable и pre-paginated items, повторять один manifest
resource несколькими `itemref`, задавать overrides на каждом occurrence и
содержать `linear="no"` цели. План поэтому вводит отдельные
`PublisherPublication`/`PublisherSpineItem` и типизированные
`ReaderLocation.Reflow`/`ReaderLocation.Publisher`, сохраняя один общий logical
reading order. Progress считается по linear occurrence, а synthetic blank,
spread slot, panel и linked document его не меняют.

Implementation разделён на три проверяемых среза:

1. EPUB fixed-layout foundation: OPF `rendition:*`, viewport/SVG `viewBox`,
   stable occurrence id, local resource session, одна fixed page и typed
   navigation/progress/back.
2. Spreads и mixed spine: LTR/RTL planner, placement/blank/true-spread,
   rotation restore, reflow↔fixed sequence, publisher search/bookmarks и
   vertical writing на browser surface.
3. KF8 fixed-layout: только доказанные retained metadata, затем typed Kindle
   magnification/text popup/panel regions и virtual-panel fallback.

Локальная publisher surface должна использовать отдельный HTTPS app-assets
origin на публикацию; JavaScript, network, file/content access, forms, frames и
media выключены. WebView не попадает в `BookContent`/disk cache, а runtime
session закрывается ViewModel. Эти инварианты требуют instrumentation на API 26
и актуальном Android до включения surface в production.

### P1 — fidelity обычных reflowable книг

- borders/radius/shadow и ограниченный безопасный box painting в Compose;
- white-space/word-break/overflow-wrap, letter/word spacing, text-transform,
  visibility и поддержанные font-feature/variant свойства;
- richer object/picture/srcset/resource fallback, URL query/base semantics и
  external references внутри SVG;
- EPUB page-list, landmarks/guide/start-reading, multiple rootfiles/renditions;
- table vertical-align/colgroup/nested blocks и более полный caption model;
- accessibility roles/labels/DPUB-ARIA, SVG/MathML text в search/selection;
- corpus реальных книг: отдельные MOBI6 `.mobi` и KF8 `.azw3`, а не выводы по
  одному combo-файлу.

### P2 — tolerant/редкие расширения

- повреждённые CSS selectors/declarations, duplicate ids и странные encodings;
- vendor prefixes, converter-specific note conventions и obsolete HTML;
- WebP/BMP/AVIF и animated GIF на разных Android image decoders;
- extreme nested lists/sections/tables/import graphs с graceful truncation и
  диагностикой вместо crash/ANR.

## Исполняемые доказательства и границы тестов

`FormatParityTest` строит четыре временных эквивалентных книги — FB2, EPUB,
MOBI6 и KF8 — и сравнивает normalized model snapshot для пяти одинаковых cases:

1. rich heading + bold/italic runs;
2. обычный paragraph с END alignment;
3. обычная cross-reference против настоящей footnote;
4. inline и block PNG с одинаковыми декодируемыми bytes;
5. table header/grid/rowspan/colspan/cell alignment.

Этот gate намеренно не притворяется pixel test и не объявляет ложный паритет
для возможностей, которых нет в FB2. Отдельные suites проверяют CSS cascade,
HTML5 inline semantics, FB2 poems/styles/languages, EPUB package/navigation,
MOBI6 и KF8 независимо, pagination metrics, table grid и link routing. Отдельно
проверяются EPUB2 DTBook package/NCX, MathML linear fallback, rich notes с
таблицей и изображениями, API 26 import path и adversarial resource budgets.

Постоянный FrogCompare corpus теперь материализует 132 нумерованных cases в
отдельных `.fb2`, `.epub`, legacy `.mobi` и pure `.azw3`, плюс отдельный EPUB 2
DTBook fixture. Источники и проверка детерминированных SHA-256 лежат в
`app/src/test/java/com/example/frogreader/testbooks/`; генерация запускается
явно через `-PgenerateTestBooks=true`. Для EPUB всё ещё нужно постепенно
подключать релевантные reflow cases из официального W3C EPUB tests suite.
Синтетический fixture доказывает точный инвариант; реальная книга доказывает,
что мы правильно поняли экосистему.

## Обязательный ручной device-gate (Pixel 9a)

Автоматизация телефона не выполняется. Владелец вручную проверяет:

1. H1–H6: шесть видимо разных размеров при малом/среднем/максимальном base font.
2. Publisher `font-size:1em` действительно заменяет semantic heading size.
3. Таблица: inherited size/family/italic/bold/line-height, широкие columns,
   rowspan и повтор header после page break.
4. EPUB `linear="no"`: открытие из текста и Contents, linked→main, linked→linked,
   Back без скачка progress и без ложного completion.
5. Animated GIF в FB2 и EPUB; static fallback при decoder failure.
6. Mixed SVG shapes+text+raster, standalone SVG page и missing-resource alt.
7. Embedded regular/bold/italic fonts на API 26 compatibility path.
8. Cross-reference не открывается как note; настоящая note не прыгает как TOC.
9. Одинаковая сложная таблица/заголовок/картинка в четырёх форматах рядом.
10. Rich note длиннее 700 символов: заголовок, список/стих, таблица, block и
    inline image, note→note и возврат в основной текст.
11. EPUB2 DTBook: NCX переход на fragment, level1–level6, nested list, poem,
    SVG и table; контент не исчезает из-за media type.
12. MathML: inline scripts/fraction/root и display matrix/limits остаются
    читаемыми, выделяемыми и не ломают пагинацию; сравнение не выдаётся за
    pixel-identical browser MathML.
13. Author colors в обычном тексте, inline span, heading/drop-cap, quote,
    table/cell и rich note: точная пара видна с Publisher formatting, полностью
    исчезает без него, одиночный цвет не становится невидимым в OLED/sepia.
14. Smart return: internal link/Contents/search/bookmark/quote/progress scrub и
    дальний page/scroll jump; точный возврат в обоих режимах, nested note и
    linked document, отсутствие phantom history после cancel/no-op, правая
    кнопка не перекрывает нижний bar и исчезает автоматически.
15. Arabic/Hebrew: mixed RTL/LTR с цифрами и скобками, `dir=auto`, `bdi`/`bdo`,
    headings/lists/table, logical margins, page order/tap zones, selection,
    search/copy в page и scroll режимах.

До этой проверки формулировка — «код и автоматические gates зелёные», а не
«визуально лучший движок на рынке уже доказан».

## Нормативные и справочные источники

- [EPUB 3.3 — W3C Recommendation](https://www.w3.org/TR/epub-33/)
- [EPUB Reading Systems 3.3](https://www.w3.org/TR/epub-rs-33/)
- [W3C EPUB tests](https://w3c.github.io/epub-tests/index.html)
- [EPUBCheck test suite, включая EPUB 2 DTBook](https://www.w3.org/publishing/epubcheck/docs/test-suite/)
- [CSS Color Module Level 4](https://www.w3.org/TR/css-color-4/)
- [Android Developers: local content in WebView](https://developer.android.com/develop/ui/views/layout/webapps/load-local-content)
- [AndroidX WebKit releases](https://developer.android.com/jetpack/androidx/releases/webkit)
- [FictionBook 2.x XSD](https://github.com/gribuser/fb2/blob/master/FictionBook.xsd)
- [Amazon Kindle Publishing Guidelines](https://kdp.amazon.com/en_US/help/topic/GU72M65VRFPH43L6)
- [HTML/CSS supported in Kindle Format 8](https://kdp.amazon.com/en_US/help/topic/GG5R7N649LECKP7U)
- [Amazon Kindle media queries (`amzn-mobi` / `amzn-kf8`)](https://kdp.amazon.com/en_US/help/topic/GR4KL488MXKPZ5BK)
- [Library of Congress: Mobipocket format description](https://www.loc.gov/preservation/digital/formats/fdd/fdd000472.shtml)
- [MobileRead MOBI container reference](https://wiki.mobileread.com/wiki/MOBI)

EPUB/FB2 имеют открытые normative schemas/specifications. Binary MOBI/KF8
container полностью и актуально Amazon не стандартизует публично, поэтому его
low-level record parsing дополнительно сверяется с независимыми реализациями и
реальными файлами; publisher-facing HTML/CSS claims берутся из Amazon docs.
