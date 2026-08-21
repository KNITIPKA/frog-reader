# Аудит движка форматов FrogReader

Дата среза: 2026-08-15. Область: FB2 2.x, EPUB 2 / EPUB 3.3,
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

Проверка шла в четыре слоя: нормативные возможности формата, распаковка и
структура пакета, преобразование в общую модель `BookContent`, затем измерение
и рисование Jetpack Compose. Зелёный parser test не считается доказательством,
если общая модель или renderer затем теряет результат.

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
fixed-layout, MathML, вертикальное письмо, абсолютное позиционирование и
сложные SVG/HTML accessibility trees.

Поэтому путь к действительно лучшему движку двухконтурный:

1. Сохранить Compose-reflow как основной быстрый и удобный путь для прозы.
2. Добавить publisher-layout surface для fixed-layout, MathML и документов,
   которые требуют web/SVG layout; выбор должен делаться по package metadata и
   фактически используемым возможностям, а не по расширению файла.

Без второго контура обещание полного EPUB/KF8-паритета было бы технически
нечестным. Исправления этого прохода максимально расширяют безопасную общую
часть и явно фиксируют оставшуюся границу.

## Исправления, внесённые по результатам аудита

- Заголовок теперь хранит `AnnotatedString`: strong/emphasis, ссылки,
  superscript/subscript и inline-изображения больше не уплощаются в строку.
- Пагинация и renderer измеряют и рисуют rich heading с теми же placeholders,
  что и абзацы.
- Обычные fragment-ссылки отделены от настоящих `noteref`; popup-сноски больше
  не поглощают содержание и перекрёстные ссылки.
- Inline `<img>` остаётся в исходной позиции текста; крупные и float-images
  остаются блочными/обтекаемыми без дублирования.
- Embedded SVG сохраняется целиком, включая одновременные vector shapes, text
  и raster `<image>`; локальный raster в сериализованном SVG встраивается как
  data URI.
- Standalone SVG в EPUB spine открывается как полноценная страница-картинка.
- `<pre>` сохраняет пробелы и переводы строк и использует monospace.
- GIF действительно декодируется и анимируется на API 26+; раньше наличие
  `coil-svg` не означало поддержку GIF-анимации.
- `alt`, `aria-label` и `title` доходят до block-image accessibility; при
  отсутствующем ресурсе текстовый fallback остаётся видимым.
- CSS `@import` в EPUB разрешается рекурсивно относительно импортирующего
  файла, с защитой от циклов.
- CSS media types теперь различают screen/print/speech, `not screen`,
  `not print` и comma alternatives; print-правила не протекают в reader.
- EPUB проходит manifest fallback chain, уважает объявленный `spine@toc` NCX,
  tokenized `properties`, literal `+` в ZIP path и standalone SVG spine.
- Legacy FB2EPUB notes распознаются только по совокупности converter metadata,
  bracketed marker, `ch2-N.xhtml` и fragment — без глобальной ложной эвристики.
- FB2 исправляет first-body semantics, полный poem/stanza/subtitle/date,
  rich titles, inline images в стихах/таблицах, SVG/GIF MIME extraction,
  image alt fallback, обычные anchors и разделение link/noteref.
- MOBI6 и KF8 отдельно фильтруют `amzn-mobi`/`amzn-kf8`, сохраняют legacy CSS,
  NCX/INDX navigation и разделяют filepos navigation от реальных note markers.
- Legacy HTML `align=` и `<center>` наследуются блоками; CSS остаётся сильнее.
- Legacy `<font size/face>` больше не теряет размер и generic font family.

## Единый нумерованный capability checklist

Одинаковые номера намеренно применяются ко всем четырём колонкам. Так любой
регрессионный corpus и ручная книга могут ссылаться на один case ID независимо
от контейнера.

### A. Контейнер, metadata и ресурсы

| № | Возможность | FB2 | EPUB | MOBI6 | KF8/AZW3 |
|---:|---|:---:|:---:|:---:|:---:|
| 1 | Сигнатура/контейнер определяется по содержимому | ✅ | ✅ | ✅ | ✅ |
| 2 | Корректная кодировка текста | ✅ | ✅ | ✅ | ✅ |
| 3 | Сжатие и безопасные границы ресурсов | ✅ | ◐ | ✅ | ✅ |
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
| 17 | Дополнительные/non-linear bodies/resources | ◐ | ❌ | Ø | ◐ |
| 18 | Вложенная иерархия section/chapter | ✅ | ✅ | ✅ | ✅ |
| 19 | TOC/NCX/INDX labels и depth | ✅ | ◐ | ✅ | ✅ |
| 20 | Несколько TOC targets в одном content file | ✅ | ❌ | ✅ | ◐ |
| 21 | Page-list, landmarks, guide/start-reading | Ø | ❌ | ❌ | ❌ |
| 22 | IDs/anchors на обычных блоках | ◐ | ✅ | ✅ | ✅ |
| 23 | Обычные внутренние cross-references | ✅ | ✅ | ✅ | ✅ |
| 24 | Семантические footnotes/endnotes | ◐ | ◐ | ✅ | ✅ |
| 25 | Backlink остаётся навигацией, а не popup-note | ✅ | ✅ | ✅ | ✅ |
| 26 | Безопасные HTTP(S)/mailto/tel links | ✅ | ✅ | ✅ | ✅ |
| 27 | EPUB CFI / Kindle locations / переносимые ranges | Ø | ❌ | ❌ | ❌ |
| 28 | Author page breaks before block | ◐ | ✅ | ✅ | ✅ |
| 29 | Break after/inside, widows/orphans | Ø/◐ | ❌ | ❌ | ❌ |
| 30 | RTL page progression / spreads | Ø | ❌ | Ø | ❌ |

EPUB `linear="no"` корректно исключается из последовательного листания, но
пока также не загружается для перехода по ссылке. Popup-note нормализует rich
text, но сложные изображения/таблицы/списки note body ещё не имеют отдельного
полноценного sheet renderer.

### C. Текстовая структура и inline formatting

| № | Возможность | FB2 | EPUB | MOBI6 | KF8/AZW3 |
|---:|---|:---:|:---:|:---:|:---:|
| 31 | Paragraphs и mixed inline content | ✅ | ✅ | ✅ | ✅ |
| 32 | Rich multi-line headings/titles | ✅ | ✅ | ✅ | ✅ |
| 33 | Subtitle | ✅ | ◐ | ◐ | ◐ |
| 34 | Bold/strong и italic/emphasis | ✅ | ✅ | ✅ | ✅ |
| 35 | Underline, strike/del/ins | ✅ | ✅ | ✅ | ✅ |
| 36 | Superscript/subscript | ✅ | ✅ | ✅ | ✅ |
| 37 | Code/monospace и preformatted whitespace | ✅/Ø | ✅ | ✅ | ✅ |
| 38 | Quote/blockquote/cite/epigraph | ✅ | ✅ | ✅ | ✅ |
| 39 | Poem, stanza, verse, text-author, date | ✅ | ◐ | Ø/◐ | ◐ |
| 40 | Ordered/unordered/nested lists | Ø | ✅ | ✅ | ✅ |
| 41 | Definition lists | Ø | ◐ | ◐ | ◐ |
| 42 | Ruby | Ø | ◐ | Ø | ◐ |
| 43 | `<q>` с языковыми кавычками | Ø | ✅ | Ø/⚠ | ✅ |
| 44 | `<mark>` semantic highlight | Ø | ✅ | Ø/⚠ | ✅ |
| 45 | `<wbr>` break opportunity | Ø | ✅ | Ø | ✅ |
| 46 | `<nobr>` / CSS white-space modes | Ø | ❌ | ◐ | ❌ |
| 47 | Generated `::before`/`::after` strings | Ø/◐ | ◐ | ⚠ | ◐ |
| 48 | Drop caps / `::first-letter` | Ø/◐ | ✅ | ⚠ | ✅ |
| 49 | Block `lang`/`xml:lang` | ◐ | ✅ | ◐ | ✅ |
| 50 | Inline span language | ◐ | ✅ | ❌ | ✅ |
| 51 | `dir`/CSS `direction` на блоке | Ø/◐ | ✅ | ◐ | ✅ |
| 52 | `bdi`/`bdo`/`unicode-bidi` | Ø | ❌ | Ø | ❌ |
| 53 | Vertical writing/text orientation/combine | Ø | ❌ | Ø | ❌ |

Ruby сейчас является понятной деградацией «base + маленький superscript rt»,
но не настоящим межстрочным ruby layout. Unicode bidi Compose работает, однако
inline isolation/override и вертикальное CJK требуют fidelity renderer.

### D. CSS и геометрия reflowable layout

| № | Возможность | FB2 | EPUB | MOBI6 | KF8/AZW3 |
|---:|---|:---:|:---:|:---:|:---:|
| 54 | Stylesheet и inline `style` | ◐ | ✅ | ◐ | ✅ |
| 55 | Cascade/specificity/inheritance/`!important` | ◐ | ✅ | ◐ | ✅ |
| 56 | Class/id/tag/attribute/combinator selectors | ◐ | ◐ | ◐ | ◐ |
| 57 | Structural pseudo-classes | Ø/◐ | ◐ | ⚠ | ◐ |
| 58 | CSS custom properties и `calc()` subset | Ø/◐ | ◐ | ⚠ | ◐ |
| 59 | Local recursive `@import` | ◐ | ✅ | Ø/⚠ | ❌ |
| 60 | Screen/print и Kindle media types | Ø/◐ | ◐ | ✅ | ✅ |
| 61 | Device width/aspect/orientation queries | Ø | ❌ | Ø/⚠ | ❌ |
| 62 | Font family/style/weight/size | ◐ | ✅ | ✅ | ✅ |
| 63 | Inline named embedded font family | Ø | ◐ | Ø | ◐ |
| 64 | Line-height и hyphenation hints | ◐ | ✅ | ✅ | ✅ |
| 65 | Text align/justify/indent | ✅ | ✅ | ✅ | ✅ |
| 66 | Margins/padding/centered boxes | ◐ | ◐ | ◐ | ◐ |
| 67 | Foreground color и background | ❌ | ❌ | ❌ | ❌ |
| 68 | Borders/radius/outline/shadow | ❌ | ❌ | ❌ | ❌ |
| 69 | Letter/word spacing, transform, text-shadow | ❌ | ❌ | ❌ | ❌ |
| 70 | Image float и basic text wrapping | Ø/◐ | ✅ | ✅ | ✅ |
| 71 | Clear/overflow/object-fit/min-max/aspect-ratio | Ø | ❌ | ❌ | ❌ |
| 72 | Absolute/fixed positioning, z-index, transform | Ø | ❌ | Ø | ❌ |
| 73 | Flex/grid/columns | Ø | ❌ | Ø | ❌ |
| 74 | Full table CSS/border-collapse/layout | ◐ | ❌ | ❌ | ❌ |
| 75 | `@page`, named pages, page floats | Ø | ❌ | Ø | ❌ |

FB2 stylesheet support — это compatibility-профиль поверх семантического XML,
а не браузерная обязанность. EPUB/KF8, напротив, реально умеют гораздо больше
CSS, поэтому строки 67–75 являются подтверждёнными reader gaps, а не пределом
форматов.

### E. Изображения, SVG, таблицы и специальные режимы

| № | Возможность | FB2 | EPUB | MOBI6 | KF8/AZW3 |
|---:|---|:---:|:---:|:---:|:---:|
| 76 | JPEG/PNG и block images | ✅ | ✅ | ✅ | ✅ |
| 77 | GIF animation | ✅ | ✅ | ⚠ | ⚠ |
| 78 | WebP/BMP/AVIF tolerant decode | ✅ | ✅/⚠ | ⚠ | ⚠ |
| 79 | Inline images в исходной позиции | ✅ | ✅ | ✅ | ✅ |
| 80 | Float images без дублирования | Ø/◐ | ✅ | ✅ | ✅ |
| 81 | Width/height/aspect preservation | ◐ | ◐ | ✅ | ✅ |
| 82 | `alt`/`aria-label`/`title`, missing fallback | ✅ | ✅ | ✅ | ✅ |
| 83 | SVG binary/by-reference | ✅ | ◐ | Ø/⚠ | ◐ |
| 84 | Mixed inline SVG shapes + text + raster image | Ø | ◐ | Ø | ◐ |
| 85 | Standalone SVG content page | Ø | ◐ | Ø | ❌ |
| 86 | SVG links/search/CSS/fonts/resource origin | Ø | ❌ | Ø | ❌ |
| 87 | Table grid/header/caption | ✅ | ✅ | ✅ | ✅ |
| 88 | Colspan/rowspan | ◐ | ◐ | ◐ | ◐ |
| 89 | Cell align | ✅ | ✅ | ✅ | ✅ |
| 90 | Cell vertical align/colgroup/complex nested cells | ❌ | ❌ | ❌ | ❌ |
| 91 | Table splitting и repeated header | ✅ | ✅ | ✅ | ✅ |
| 92 | Presentation MathML | Ø | ❌ | Ø | Ø |
| 93 | Fixed-layout pages/spreads/orientation | Ø | ❌ | Ø | ❌ |
| 94 | KF8 panels/region magnification/text popups | Ø | Ø | Ø | ❌ |
| 95 | Script/forms/canvas/iframe | Ø | ❌/optional | Ø | Ø |
| 96 | Audio/video/media overlays | Ø | ⏸ | Ø | ⏸/Ø |
| 97 | Semantic accessibility tree/ARIA/DPUB-ARIA | ◐ | ❌ | Ø/◐ | ❌ |
| 98 | Search/selection по normal text | ✅ | ✅ | ✅ | ✅ |
| 99 | Search/selection по SVG/MathML/nonlinear content | Ø | ❌ | Ø | ❌ |
| 100 | Large-file bounds, damaged input degradation | ✅ | ◐ | ✅ | ✅ |

`⏸` — явно отложенные audio/video. Для KF8 официальная support table также
помечает обычные HTML audio/video как неподдерживаемые, несмотря на отдельные
исторические Kindle publishing workflows.
