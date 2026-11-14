# design.md: як зробити апку, що виглядає й поводиться як рідна апка Google на Pixel

Версія файлу: 2026-11-13. Ціль: Pixel 10 Pro XL, Android 17 QPR2 (бета QPR3), Kotlin + Compose, `androidx.compose.material3:material3:1.5.0-rc01` явно поверх BOM `2026.09.00`.

## 0. Як читати цей файл

Кожне значення має позначку походження:

| Позначка | Що означає |
|---|---|
| **[код m3]** | `material3-android-1.5.0-rc01-sources.jar` з Google Maven, файл указано поруч. Найвищий рівень довіри. |
| **[семпл]** | Офіційні семпли `androidx-main` (github.com/androidx/androidx). API звірено з sources jar 1.5.0-rc01, але семпли можуть бути новішими за rc01. |
| **[AOSP]** | `platform/frameworks/base`, гілка `main` станом на 12.11.2026. Це не тег QPR2, тому значення варто перевіряти після кожного QPR. |
| **[MDC]** | `material-components-android`, гілка `master` (специфікація View-переходів Material). |
| **[docs]** | developer.android.com / m3.material.io. |
| **[медіа]** | Android Authority, 9to5Google тощо. Лише для фактів про поведінку Pixel, не для чисел. |
| **[аналіз]** | Мій висновок чи адаптація значень. Не першоджерело. |
| **[не знайдено]** | Шукав, у першоджерелах немає. Не вигадувати. |

Код-приклади написані проти сигнатур із sources jar 1.5.0-rc01, але **не компілювались**. Перша збірка в скілі має їх підтвердити.

---

## 1. Тема: фундамент, без якого все інше не працює

### 1.1 Тільки `MaterialExpressiveTheme`

- `MaterialTheme(...)` за замовчуванням бере `motionScheme = MotionScheme.standard()` **[код m3: MaterialTheme.kt]**. Пружини там жорсткіші й без відскоку, тобто виходить «старий» M3.
- `MaterialExpressiveTheme(...)` бере `MotionScheme.expressive()` і `expressiveLightColorScheme()`, якщо не передати свої **[код m3: MaterialTheme.kt]**. У 1.5.0-rc01 він **стабільний** (без `@Experimental`).
- `expressiveLightColorScheme()` відрізняється від `lightColorScheme()` лише тим, що `on*Container` беруть тон **30**, а не 10 **[код m3: ColorScheme.kt]**. Це запасний варіант на випадок, коли динамічних кольорів немає.

```kotlin
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    // minSdk 35, тому dynamic color є завжди. На API 34+ схема читається
    // прямо з системних атрибутів (mc3_scheme_*), тобто кольори 1:1 як у Settings [код m3: DynamicTonalPalette.android.kt]
    val colors = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    MaterialExpressiveTheme(
        colorScheme = colors,
        motionScheme = MotionScheme.expressive(),
        typography = AppTypography, // див. 1.2
        content = content,
    )
}
```

### 1.2 Шрифт: Google Sans Flex

- Google Sans і Google Sans Flex з листопада 2025 доступні під SIL Open Font License **[docs: developers.google.com/fonts/faq]**. Їх можна класти в APK.
- Файл у репозиторії google/fonts: `ofl/googlesansflex/GoogleSansFlex[ROND,opsz,slnt,wdth,wght].ttf`. Осі: `ROND` 0..100, `opsz` 1..144, `slnt` -10..0, `wdth` 25..151, `wght` від 1 **[google/fonts METADATA.pb]**. Звичайний Google Sans має осі `GRAD` -50..200, `opsz` 17..18, `wght` 400..700.
- [код] Вихідний проєкт Google Sans Flex задає діапазон ROND 0..100 і типовий 0. [не знайдено] Точні значення осей у прошивці Pixel QPR2 і стабільний публічний системний псевдонім. [аналіз] Для відтворюваної типографіки покласти OFL-файл і ліцензію в проєкт. https://github.com/googlefonts/googlesans-flex

```kotlin
private fun gsf(weight: Int) = Font(
    R.font.google_sans_flex,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)
val GoogleSansFlex = FontFamily(gsf(400), gsf(500), gsf(700))

// Розміри та трекінг НЕ змінюємо: беремо токени m3 і лише підставляємо родину шрифту.
val AppTypography = Typography().let { t ->
    fun TextStyle.g() = copy(fontFamily = GoogleSansFlex)
    Typography(
        displayLarge = t.displayLarge.g(), displayMedium = t.displayMedium.g(), displaySmall = t.displaySmall.g(),
        headlineLarge = t.headlineLarge.g(), headlineMedium = t.headlineMedium.g(), headlineSmall = t.headlineSmall.g(),
        titleLarge = t.titleLarge.g(), titleMedium = t.titleMedium.g(), titleSmall = t.titleSmall.g(),
        bodyLarge = t.bodyLarge.g(), bodyMedium = t.bodyMedium.g(), bodySmall = t.bodySmall.g(),
        labelLarge = t.labelLarge.g(), labelMedium = t.labelMedium.g(), labelSmall = t.labelSmall.g(),
        displayLargeEmphasized = t.displayLargeEmphasized.g(), displayMediumEmphasized = t.displayMediumEmphasized.g(),
        displaySmallEmphasized = t.displaySmallEmphasized.g(), headlineLargeEmphasized = t.headlineLargeEmphasized.g(),
        headlineMediumEmphasized = t.headlineMediumEmphasized.g(), headlineSmallEmphasized = t.headlineSmallEmphasized.g(),
        titleLargeEmphasized = t.titleLargeEmphasized.g(), titleMediumEmphasized = t.titleMediumEmphasized.g(),
        titleSmallEmphasized = t.titleSmallEmphasized.g(), bodyLargeEmphasized = t.bodyLargeEmphasized.g(),
        bodyMediumEmphasized = t.bodyMediumEmphasized.g(), bodySmallEmphasized = t.bodySmallEmphasized.g(),
        labelLargeEmphasized = t.labelLargeEmphasized.g(), labelMediumEmphasized = t.labelMediumEmphasized.g(),
        labelSmallEmphasized = t.labelSmallEmphasized.g(),
    )
}
```

---

## 2. Рух: MotionScheme

### 2.1 Пружини **[код m3: tokens/ExpressiveMotionTokens.kt, tokens/StandardMotionTokens.kt]**

| Токен | expressive: damping / stiffness | standard: damping / stiffness |
|---|---|---|
| fastSpatial | **0.6 / 800** | 0.9 / 1400 |
| defaultSpatial | **0.8 / 380** | 0.9 / 700 |
| slowSpatial | **0.8 / 200** | 0.9 / 300 |
| fastEffects | 1.0 / 3800 | 1.0 / 3800 |
| defaultEffects | 1.0 / 1600 | 1.0 / 1600 |
| slowEffects | 1.0 / 800 | 1.0 / 800 |

Висновки:
- Effects у двох схемах **однакові** й завжди без відскоку (damping 1.0). Різниця між схемами лише в spatial.
- **spatial** застосовуємо до того, що рухається або змінює розмір і форму: позиція, розмір, кути, морфінг, ширина кнопок у ButtonGroup. Тут дозволений відскік.
- **effects** застосовуємо до кольору, прозорості, elevation. Відскоку бути не може.
- `ButtonGroup` всередині бере `FastSpatial` **[код m3: ButtonGroup.kt]**. Для мікровзаємодій (натиск, перемикач) беремо `fast*`, для переходів між станами екрана `default*`, для великих hero-змін `slow*`.

```kotlin
val ms = MaterialTheme.motionScheme
val corner by animateDpAsState(if (pressed) 8.dp else 28.dp, ms.fastSpatialSpec(), label = "corner")
val bg by animateColorAsState(if (on) cs.primaryContainer else cs.surfaceContainerHigh, ms.defaultEffectsSpec(), label = "bg")
```

### 2.2 Тривалості й криві (коли потрібен `tween`, а не пружина) **[код m3: tokens/MotionTokens.kt]**

| Токен | мс | | Крива | CubicBezier |
|---|---|---|---|---|
| Short1..4 | 50, 100, 150, 200 | | Emphasized (наближення) | 0.2, 0, 0, 1 |
| Medium1..4 | 250, 300, 350, 400 | | EmphasizedDecelerate | 0.05, 0.7, 0.1, 1 |
| Long1..4 | 450, 500, 550, 600 | | EmphasizedAccelerate | 0.3, 0, 0.8, 0.15 |
| ExtraLong1..4 | 700, 800, 900, 1000 | | Standard | 0.2, 0, 0, 1 |
| | | | StandardDecelerate / Accelerate | 0, 0, 0, 1 / 0.3, 0, 1, 1 |

**Справжня** emphasized-крива системи (WM Shell, `Interpolators.EMPHASIZED`) не є одним CubicBezier. Це двосегментний шлях «fast_out_extra_slow_in» **[AOSP: libs/WindowManager/Shell/shared/.../Interpolators.java]**. У Compose його точна копія:

```kotlin
val EmphasizedPath = PathEasing(Path().apply {
    moveTo(0f, 0f)
    cubicTo(0.05f, 0f, 0.133333f, 0.06f, 0.166666f, 0.4f)
    cubicTo(0.208333f, 0.82f, 0.25f, 1f, 1f, 1f)
})
```

---

## 3. Форми

### 3.1 Кути **[код m3: tokens/ShapeTokens.kt]**

| Токен | dp | Де |
|---|---|---|
| ExtraSmall | 4 | середні рядки SegmentedListItem |
| Small | 8 | внутрішні кути connected-кнопок |
| Medium | 12 | |
| Large | 16 | крайні кути списку-групи, натиснутий і вибраний рядок |
| LargeIncreased | 20 | |
| ExtraLarge | 28 | картки-hero, bottom sheet |
| ExtraLargeIncreased | 32 | |
| ExtraExtraLarge | 48 | |
| Full | коло | кнопки, FAB, чипи |

### 3.2 MaterialShapes **[код m3: MaterialShapes.kt]**

Рівно 35 форм: Circle, Square, Slanted, Arch, Fan, Arrow, SemiCircle, Oval, Pill, Triangle, Diamond, ClamShell, Pentagon, Gem, Sunny, VerySunny, Cookie4Sided, Cookie6Sided, Cookie7Sided, Cookie9Sided, Cookie12Sided, Ghostish, Clover4Leaf, Clover8Leaf, Burst, SoftBurst, Boom, SoftBoom, Flower, Puffy, PuffyDiamond, PixelCircle, PixelTriangle, Bun, Heart.

**Важливо:** у 1.5.0-rc01 `MaterialShapes`, `RoundedPolygon.toShape()`, `RoundedPolygon.toPath()` і `Morph.toPath()` досі позначені `@ExperimentalMaterial3ExpressiveApi` **[код m3]**. Потрібен `@OptIn`.

Морфінг-форма для статусу (наприклад, «з'єднано» перетворюється на «шукаю»):

```kotlin
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
class MorphShape(private val morph: Morph, private val progress: () -> Float) : Shape {
    private val matrix = Matrix()
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = morph.toPath(progress())          // нормалізовано в 0..1
        matrix.reset(); matrix.scale(size.width, size.height)
        path.transform(matrix)
        return Outline.Generic(path)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun StatusBlob(connected: Boolean, modifier: Modifier = Modifier) {
    val morph = remember { Morph(MaterialShapes.Cookie9Sided.normalized(), MaterialShapes.Circle.normalized()) }
    val p by animateFloatAsState(if (connected) 1f else 0f, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "morph")
    val color by animateColorAsState(
        if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        MaterialTheme.motionScheme.defaultEffectsSpec(), label = "c")
    Box(modifier.size(96.dp).clip(MorphShape(morph) { p }).background(color))
}
```

Так само робить сам `LoadingIndicator`: `Morph(polygons[i].normalized(), polygons[i+1].normalized())` **[код m3: LoadingIndicator.kt]**.

---

## 4. Типографіка **[код m3: tokens/TypeScaleTokens.kt, tokens/TypefaceTokens.kt]**

Emphasized-стиль має **той самий розмір і висоту рядка**, змінюється лише вага (і інколи трекінг). Тому перемикання base і emphasized не стрибає версткою.

| Роль | sp / line height | Base: вага, трекінг | Emphasized: вага, трекінг |
|---|---|---|---|
| displayLarge | 57 / 64 | 400, -0.2 | 500, 0 |
| displayMedium | 45 / 52 | 400, 0 | 500, 0 |
| displaySmall | 36 / 44 | 400, 0 | 500, 0 |
| headlineLarge | 32 / 40 | 400, 0 | 500, 0 |
| headlineMedium | 28 / 36 | 400, 0 | 500, 0 |
| headlineSmall | 24 / 32 | 400, 0 | 500, 0 |
| titleLarge | 22 / 28 | 400, 0 | 500, 0 |
| titleMedium | 16 / 24 | 500, 0.2 | 700, 0.15 |
| titleSmall | 14 / 20 | 500, 0.1 | 700, 0.1 |
| bodyLarge | 16 / 24 | 400, 0.5 | 500, 0.15 |
| bodyMedium | 14 / 20 | 400, 0.2 | 500, 0.25 |
| bodySmall | 12 / 16 | 400, 0.4 | 500, 0.4 |
| labelLarge | 14 / 20 | 500, 0.1 | 700, 0.1 |
| labelMedium | 12 / 16 | 500, 0.5 | 700, 0.5 |
| labelSmall | 11 / 16 | 500, 0.5 | 700, 0.5 |

Правила **[аналіз]**:
- Emphasized лише для одного елемента на екрані (hero-значення, вибраний пункт, головна дія), а не для всіх заголовків.
- `LargeFlexibleTopAppBar` сам бере `DisplaySmall` для розгорнутого заголовка й `TitleMedium` для підзаголовка **[код m3: tokens/AppBarLargeFlexibleTokens.kt]**. Розмір не перевизначаємо.
- Цифри, що змінюються, завжди з `fontFeatureSettings = "tnum"` (табличні), інакше ширина рядка «дихає».

---

## 5. Колір

### 5.1 Ролі поверхонь (тон палітри neutral) **[код m3: tokens/ColorLightTokens.kt, tokens/ColorDarkTokens.kt]**

| Роль | Світла | Темна |
|---|---|---|
| surfaceContainerLowest | N100 | N4 |
| surfaceContainerLow | N96 | N10 |
| surface / background | N98 | N6 |
| surfaceContainer | N94 | N12 |
| surfaceContainerHigh | N92 | N17 |
| surfaceContainerHighest | N90 | N22 |
| surfaceDim / surfaceBright | N87 / N98 | N6 / N24 |
| onSurface / onSurfaceVariant | N10 / NV30 | N90 / NV80 |
| outline / outlineVariant | NV50 / NV80 | NV60 / NV30 |

Це базові токени. На пристрої з динамічним кольором тони рахує система (API 34+ читаються прямо з неї). Відношення «вище за списком означає світліше у світлій темі і темніше в темній» зберігається.

### 5.2 Правила кольору

1. **Ієрархія поверхонь, а не тіні.** Фон екрана `surface`, групи списків `surfaceContainer`. Саме так зроблено в офіційних семплах `SegmentedListItem`: `ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer)` **[семпл: ListSamples.kt]**. Hero-картка `surfaceContainerHigh` або `primaryContainer`.
2. **Текст тільки `on*`-ролями.** На `surface*` основний текст `onSurface`, другорядний `onSurfaceVariant`. Ніяких `Color.Gray`, `alpha = 0.6f`, `LocalContentColor.copy(alpha)`.
3. **Вимкнений стан** = `onSurface` з альфою 0.38 **[код m3: tokens/ListTokens.kt, ItemDisabled*Opacity]**. Це єдине місце, де «сірий» допустимий, і лише для справді вимкненого.
4. **Статус акцентами:** «увімкнено, з'єднано» `primary`/`primaryContainer`; «увага» `tertiary`/`tertiaryContainer`; «помилка» `error`/`errorContainer`; «нейтрально, вимкнено свідомо» `surfaceContainerHighest` + `onSurfaceVariant`.
5. Вибраний рядок списку отримує `secondaryContainer` **[код m3: ListTokens.ItemSelectedContainerColor]**.

---

## 6. Компоненти M3 Expressive: що, коли і як

### 6.1 Статус у 1.5.0-rc01 **[код m3]**

| Компонент | Статус | Ключові значення |
|---|---|---|
| `MaterialExpressiveTheme` | стабільний | див. розділ 1 |
| `SegmentedListItem` (4 перевантаження: без кліку, `onClick`, `selected`, `checked`) | стабільний | gap 2 dp, середні кути 4, крайні 16, натиснутий 16, вибраний 16 |
| `ButtonGroup` (DSL: `clickableItem`, `toggleableItem`, `customItem`) | стабільний | проміжок 12 dp, висота 40, `ExpandedRatio` 0.15 |
| Connected-група (`ToggleButton` + `ButtonGroupDefaults.connected*ButtonShapes()`) | стабільний | проміжок 2 dp, внутрішні кути 8, натиснуті 4, вибрана кнопка повністю кругла |
| `LargeFlexibleTopAppBar` / `MediumFlexibleTopAppBar` | стабільний | Large: 120 dp без підзаголовка, 152 з ним; згорнутий 64 |
| `LinearWavyProgressIndicator` / `CircularWavyProgressIndicator` | стабільний | |
| `FloatingActionButtonMenu`, `ToggleFloatingActionButton`, `FloatingActionButtonMenuItem` | стабільний | |
| `HorizontalFloatingToolbar`, `FlexibleBottomAppBar`, `SplitButtonLayout` | стабільний | |
| `LoadingIndicator`, `ContainedLoadingIndicator` | **`@ExperimentalMaterial3ExpressiveApi`** | 38 dp фігура, 48 dp контейнер |
| `MaterialShapes`, `toShape`, `toPath` | **`@ExperimentalMaterial3ExpressiveApi`** | |

Немає окремого composable `ConnectedButtonGroup`: connected-групу збирають руками з `Row`/`FlowRow` **[семпл: ButtonGroupSamples.kt]**.

**Виправлення до попередніх нотаток:** натиснутий `SegmentedListItem` морфиться не до повністю круглих кутів, а до `CornerLarge` = **16 dp** (`ItemPressedContainerExpressiveShape`). Середній рядок у спокої має 4 dp, тому морф 4 → 16 dp помітний **[код m3: tokens/ListTokens.kt]**.

### 6.2 Група налаштувань як у Settings

```kotlin
@Composable
fun SettingsGroup(title: String?, rows: List<RowModel>) {
    if (title != null) Text(title, style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 32.dp, top = 24.dp, bottom = 8.dp)) // [аналіз], уточнить ресерч №2
    val colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    Column(
        Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap), // 2.dp
    ) {
        rows.forEachIndexed { i, row ->
            val shapes = ListItemDefaults.segmentedShapes(index = i, count = rows.size)
            when (row) {
                is RowModel.Toggle -> SegmentedListItem(
                    checked = row.checked, onCheckedChange = row.onChange, shapes = shapes, colors = colors,
                    leadingContent = { Icon(painterResource(row.icon), null) },
                    trailingContent = { Switch(checked = row.checked, onCheckedChange = null) },
                    supportingContent = row.summary?.let { { Text(it) } },
                ) { Text(row.title) }
                is RowModel.Nav -> SegmentedListItem(
                    onClick = row.onClick, shapes = shapes, colors = colors,
                    leadingContent = { Icon(painterResource(row.icon), null) },
                    supportingContent = row.summary?.let { { Text(it) } },
                ) { Text(row.title) }
            }
        }
    }
}
```

Для `Switch` у трейлінгу `onCheckedChange = null`: клік обробляє весь рядок, тому ціль дотику на всю ширину.

### 6.3 Вибір режиму (connected toggle group)

Ідеально для «ANC / Ambient / Off» чи «Auto / On / Off».

```kotlin
@Composable
fun <T> ModeSelector(options: List<T>, selected: T, label: (T) -> String, icon: (T, Boolean) -> Int, onSelect: (T) -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween), // 2.dp
    ) {
        options.forEachIndexed { i, opt ->
            val checked = opt == selected
            ToggleButton(
                checked = checked,
                onCheckedChange = { onSelect(opt) },
                shapes = when (i) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
            ) {
                Icon(painterResource(icon(opt, checked)), contentDescription = null)
                Spacer(Modifier.size(ToggleButtonDefaults.IconSpacing))
                Text(label(opt), maxLines = 1)
            }
        }
    }
}
```

### 6.4 ButtonGroup (дії, що «стискають» сусідів)

```kotlin
ButtonGroup(overflowIndicator = { ButtonGroupDefaults.OverflowIndicator(menuState = it) }) {
    clickableItem(onClick = onRefresh, label = "Refresh", icon = { Icon(painterResource(R.drawable.ms_refresh), null) })
    toggleableItem(checked = pinned, onCheckedChange = onPin, label = "Pin", icon = { Icon(painterResource(R.drawable.ms_keep), null) })
}
```

`ButtonGroup` приймає DSL-лямбду `ButtonGroupScope.() -> Unit`, а не `@Composable`. Звідси помилка «ButtonGroup is a DSL» **[код m3: ButtonGroup.kt]**.

### 6.5 Головний екран з великим заголовком

```kotlin
val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
Scaffold(
    modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
    topBar = {
        LargeFlexibleTopAppBar(
            title = { Text("Earbuds") },
            subtitle = { Text("WF-1000XM5") },
            scrollBehavior = scroll,
        )
    },
) { pad -> /* LazyColumn(contentPadding = pad) */ }
```

### 6.6 Прогрес і завантаження

- Визначений прогрес (завантаження файлу, заряд): `LinearWavyProgressIndicator(progress = { p })`. Анімацію значення робити через `ProgressIndicatorDefaults.ProgressAnimationSpec` **[семпл: ProgressIndicatorSamples.kt]**.
- `LoadingIndicator` без `progress` **нескінченний**. Він дозволений лише на час реальної операції, що триває довше ~300 мс **[аналіз]**, і ніколи замість відомого значення (див. правило M3).

### 6.7 FAB menu і toolbar

`FloatingActionButtonMenu` + `ToggleFloatingActionButton` + `FloatingActionButtonMenuItem`. Іконка «+» морфиться в «×» через `Modifier.animateIcon({ checkedProgress })`, а `BackHandler(expanded) { expanded = false }` закриває меню жестом назад **[семпл: FloatingActionButtonMenuSamples.kt]**. В останній пункт меню додаємо `CustomAccessibilityAction("Close menu")`, як у семплі. Для односторінкових апок FAB menu зазвичай зайве **[аналіз]**: одна головна дія це звичайна `Button`/`ExtendedFloatingActionButton`.

---

## 7. Структура екрана

### 7.1 Відступи з токенів **[код m3: tokens/ListTokens.kt]**

| Що | dp |
|---|---|
| Висота рядка 1/2/3 рядки | 56 / 72 / 88 |
| Внутрішні відступи рядка: лівий/правий | 16 / 16 |
| Верх/низ рядка | 10 / 10 |
| Між елементами рядка | 12 |
| Іконка рядка expressive / класична | 20 / 24 |
| Аватар | 40 |
| Gap між segmented-рядками | 2 |
| Висота згорнутого app bar | 64 |

### 7.2 Шаблон односторінкової утиліти **[аналіз]**

```
LargeFlexibleTopAppBar (назва апки, підзаголовок = пристрій/контекст)
Hero-блок: surfaceContainerHigh або primaryContainer, кути 28 dp, відступ 16 dp від країв
    форма-статус (MaterialShapes) + головне значення (displaySmall/Emphasized, tnum)
    головний перемикач режиму (connected group)
Група 1 (SegmentedListItem, surfaceContainer)
Група 2 ...
Внизу нічого «плаваючого», якщо немає головної дії
```

### 7.3 Патерни апок Pixel (Settings, Pixel Buds, Recorder, Clock, Weather, Files)

[не знайдено] Немає вимірів конкретних версій Settings, Pixel Buds, Recorder, Clock, Weather і Files на цільовій прошивці. Значення у 7.1 походять із Compose, а 7.2 описує наш шаблон. Для точного звірення потрібні скриншоти та щільність пристрою. Зняти початковий екран, активний і порожній стани на однаковому масштабі й записати відступи, форму груп і позицію головного елемента для кожної апки.

---

## 8. Рух і жести

### 8.1 Predictive back: значення системи **[AOSP: WM Shell back/CrossActivityBackAnimation.kt, DefaultCrossActivityBackAnimation.kt, res/values/dimen.xml]**

| Параметр | Значення | Константа |
|---|---|---|
| Масштаб сторінки, що закривається | 0.9 | `MAX_SCALE` |
| Відступ від краю екрана | 8 dp | `cross_task_back_vertical_margin` (поле `displayBoundsMargin`) |
| Звідки стартує попередня сторінка | 96 dp лівіше | `cross_activity_back_entering_start_offset` |
| Scrim (макс. альфа) | 0.8 темна, 0.2 світла | `MAX_SCRIM_ALPHA_DARK/LIGHT` |
| Тривалість після відпускання | 450 мс | `POST_COMMIT_DURATION` |
| Крива під час жесту | PathInterpolator(0.1, 0.1, 0, 1) | `BackGestureInterpolator` |
| Крива після відпускання | двосегментний emphasized (див. 2.2), **не** CubicBezier(0.2, 0, 0, 1) | `Interpolators.EMPHASIZED` |
| Зсув вправо | лише якщо жест **не** з правого краю | `swipeEdge != EDGE_RIGHT` |

**Виправлення:** у попередніх нотатках крива після відпускання була записана як `(0.2, 0, 0, 1)`. У WM Shell це шлях `cubicTo(0.05, 0, 0.133333, 0.06, 0.166666, 0.4)` + `cubicTo(0.208333, 0.82, 0.25, 1, 1, 1)`. Решта значень підтверджена.

> **Примітка про наявні апки.** Earbuds і VPN зараз використовують після відпускання жесту назад спрощену криву `CubicBezier(0.2f, 0f, 0f, 1f)`. **Еталон:** `EmphasizedPath` (`PathEasing` з AOSP, розділ 2.2). Нові апки одразу роблять за еталоном. Earbuds і VPN буде оновлено під цей файл, а до того часу ця розбіжність відома і помилкою рев'ю не вважається.

### 8.2 Navigation 3 за замовчуванням НЕ схожа на систему

`NavDisplay` за замовчуванням робить crossfade 700 мс уперед і назад, а для predictive back `fadeIn` (пружина 1.0/1600) + `scaleOut(0.7)` **[androidx-main: navigation3-ui/.../NavDisplay.android.kt]**. Це не схоже ні на Settings, ні на системний перехід. Тому специфікацію задаємо явно.

```kotlin
@Composable
fun PixelNavDisplay(backStack: SnapshotStateList<Any>, entryProvider: (Any) -> NavEntry<Any>) {
    val density = LocalDensity.current
    val widthPx = LocalWindowInfo.current.containerSize.width.toFloat()
    val enterOffset = with(density) { 96.dp.roundToPx() }
    val margin = with(density) { 8.dp.toPx() }
    // Опорна точка масштабу: права межа сторінки зупиняється за 8 dp від краю [аналіз: адаптація AOSP]
    val pivotFromLeftEdge = (1f - margin / (0.1f * widthPx)).coerceIn(0.5f, 1f)
    val slide = with(density) { 30.dp.roundToPx() } // shared axis X [MDC]
    val axis = tween<IntOffset>(450, easing = EmphasizedPath)
    val fadeOutFast = tween<Float>((450 * 0.35f).toInt(), easing = LinearEasing)
    val fadeInLate = tween<Float>((450 * 0.65f).toInt(), delayMillis = (450 * 0.35f).toInt())

    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryProvider = entryProvider,
        transitionSpec = {
            (slideInHorizontally(axis) { slide } + fadeIn(fadeInLate)) togetherWith
                (slideOutHorizontally(axis) { -slide } + fadeOut(fadeOutFast))
        },
        popTransitionSpec = {
            (slideInHorizontally(axis) { -slide } + fadeIn(fadeInLate)) togetherWith
                (slideOutHorizontally(axis) { slide } + fadeOut(fadeOutFast))
        },
        predictivePopTransitionSpec = { swipeEdge ->
            val pivotX = if (swipeEdge == NavigationEvent.EDGE_RIGHT) 0.5f else pivotFromLeftEdge
            val spec = tween<Float>(450, easing = EmphasizedPath)
            ContentTransform(
                targetContentEnter = slideInHorizontally(tween(450, easing = EmphasizedPath)) { -enterOffset } +
                    scaleIn(spec, initialScale = 0.9f),
                initialContentExit = scaleOut(spec, targetScale = 0.9f, transformOrigin = TransformOrigin(pivotX, 0.5f)),
            )
        },
    )
}
```

Scrim між сторінками й округлення кутів сторінки під час жесту в цьому коді не реалізовані. Це **[аналіз]**: наближення, обов'язково перевірити жестом на телефоні і порівняти з Settings.

### 8.3 Shared axis і container transform **[MDC: transition/MaterialSharedAxis.java, FadeThroughProvider.java, MaterialContainerTransform.java, transition/res/values/dimens.xml]**

| Перехід | Значення |
|---|---|
| Shared axis: зсув | 30 dp |
| Shared axis: тривалість | `motionDurationLong1` = 450 мс |
| Shared axis: крива | emphasized |
| Fade through: поріг | 0.35 (старе зникає за перші 35 %, нове з'являється після) |
| Container transform: вхід / вихід | `Long2` = 500 мс / `Medium4` = 400 мс, emphasized |

Container transform з картки в сторінку:

```kotlin
SharedTransitionLayout {
    AnimatedContent(targetState = openedId, label = "ct") { id ->
        if (id == null) {
            Card(
                onClick = { onOpen(item.id) },
                modifier = Modifier.sharedBounds(
                    rememberSharedContentState("card-${item.id}"), this@AnimatedContent,
                    boundsTransform = { _, _ -> tween(500, easing = EmphasizedPath) },
                    resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                ),
            ) { /* ... */ }
        } else {
            DetailPage(
                Modifier.sharedBounds(
                    rememberSharedContentState("card-$id"), this@AnimatedContent,
                    boundsTransform = { _, _ -> tween(400, easing = EmphasizedPath) },
                    resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                ),
            )
        }
    }
}
```

### 8.4 Коли анімувати, а коли ні **[аналіз; перевіряється тестами з розділу 15]**

- Анімуємо **лише зміну стану**: натиск, вибір, новий статус від пристрою, перехід між екранами.
- У спокої на екрані **нуль** запущених анімацій. `rememberInfiniteTransition` і `LoadingIndicator` без `progress` заборонені, коли немає реальної операції.
- Пульсація «пристрій підключений» заборонена. Статус показує форма й колір, що стоять нерухомо.
- Тест: після холодного старту і завершення всіх переходів `composeTestRule.mainClock.autoAdvance = false`, кілька кадрів, і перевірка, що дерево не змінюється. Плюс CI-перевірка, що у фоні немає активних wake lock і сервісів (`adb shell dumpsys power`, `dumpsys activity services <pkg>`).
- Нескінченні анімації в Compose-тестах ніколи не «заспокоюються». Для них потрібен ручний годинник.

---

## 9. Значення, що змінюються, і «останнє відоме»

### 9.1 Прокручувані цифри

```kotlin
@Composable
fun RollingNumber(value: Int, style: TextStyle, modifier: Modifier = Modifier) {
    val text = value.toString()
    val spec = MaterialTheme.motionScheme.fastSpatialSpec<IntOffset>()
    val fade = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    Row(modifier.semantics(mergeDescendants = true) { contentDescription = text }) {
        text.forEachIndexed { i, ch ->
            key(text.length - i) { // ключ від правого краю: розряди стабільні, коли довжина змінюється
                AnimatedContent(
                    targetState = ch,
                    transitionSpec = {
                        val up = targetState > initialState
                        (slideInVertically(spec) { h -> if (up) h else -h } + fadeIn(fade)) togetherWith
                            (slideOutVertically(spec) { h -> if (up) -h else h } + fadeOut(fade)) using
                            SizeTransform(clip = true)
                    },
                    label = "digit",
                ) { Text(it.toString(), style = style.copy(fontFeatureSettings = "tnum")) }
            }
        }
    }
}
```

### 9.2 Ніяких «Reading…» і стрибків верстки

- Останнє відоме значення зберігаємо в DataStore разом із часом отримання. Перший кадр **одразу** малює його.
- Якщо значення застаріле, не ховаємо його. Показуємо те саме значення, а свіжість передаємо кольором (`onSurfaceVariant` замість акценту) і маленькою міткою «2 min ago» поруч. Розмір блоку не змінюється.
- Плейсхолдер на перший запуск (ще нічого не відомо) має **ті самі розміри**, що й реальне значення: «--» тим самим стилем.
- Splash не тримаємо, чекаючи на дані: `setKeepOnScreenCondition` лише для синхронного читання DataStore, яке триває мілісекунди.

---

## 10. Віджети

### 10.1 Факти

- Кут фону: **ніколи не хардкодити**. `system_app_widget_background_radius` = 28 dp, а з фіче-флагом `use_smaller_app_widget_system_radius` 24 dp. Внутрішній радіус 20 або 16 dp **[AOSP: core/res/res/values/dimens.xml]**. Чи ввімкнено флаг на QPR2: **[не знайдено]**. У Glance: `GlanceModifier.cornerRadius(android.R.dimen.system_app_widget_background_radius)`.
- Фон з палітри шпалер: у Glance `GlanceTheme.colors.widgetBackground` = `system_accent2_50` у світлій темі і `system_accent2_800` у темній **[androidx-main: glance/src/main/res/values-v31, values-night-v31/colors.xml]**. Це вже «колір зі шпалер», а не сірий neutral.
- Root-елемент віджета: `GlanceModifier.appWidgetBackground()` (у RemoteViews `@android:id/background`). Тоді лаунчер робить плавний перехід у апку. Broadcast trampolines ламають цю анімацію **[docs: appwidgets/enhance]**.
- Розміри комірок (оцінка Google, Pixel 4, 5x4): портрет `(73n - 16) x (118m - 16)` dp, ландшафт `(142n - 15) x (66m - 15)` dp. На Android 12+ головне `targetCellWidth/Height` **[docs: appwidgets/layouts]**. Точні комірки Pixel 10 Pro XL: **[не знайдено]**, тому тільки responsive-верстка.
- Responsive: `SizeMode.Responsive(setOf(...))` з 2..3 розмірами. Система бере найбільший, що влазить.
- Canonical layouts: Text, Text and image, Search toolbar, Toolbar, Expressive toolbar, Text and image list, Checklist, Action list, Full bleed image, Image grid, Image and text grid **[docs: design/widgets/layouts]**. Full bleed image з snap-скролом вимагає Android 17, `compileSdk` 37, Glance 1.3.0-alpha02+, AGP 9.1+, `SizeMode.Exact`.
- Мінімальна кнопка у віджеті: 48 dp **[docs: design/widgets/layouts]**.
- Generated previews: Android 15+ на пристрої, `GlanceAppWidget.providePreview` + `previewSizeMode`, публікація через `GlanceAppWidgetManager.setWidgetPreviews`, ліміт приблизно 2 виклики на годину **[docs: glance/generated-previews]**. `previewImage` лишаємо як запасний варіант.
- Версії Glance на Google Maven: 1.2.0 стабільна, 1.3.0-alpha02 найновіша **[Google Maven group-index, 12.11.2026]**.

### 10.2 Екран блокування (хаб)

- На телефонах з'явився в Android 16 QPR2: окрема сторінка праворуч від екрана блокування, вмикається в Settings > Display & touch > Lock screen **[медіа: Android Authority, Droid Life]**.
- Доступні **всі** віджети без змін у коді; відмовитись можна категорією `not_keyguard`. Активність, запущена з віджета на екрані блокування, вимагає розблокування, якщо в неї немає `android:showWhenLocked="true"`. Google радить підтримувати dynamic color і зміну розміру **[docs: android-developers.googleblog.com, Widgets on lock screen FAQ]**.
- [docs] Орієнтир Google становить 4 x 3 комірки, точні dp залежать від пристрою. Розмір читаємо через LocalSize. OPTION_APPWIDGET_HOST_CATEGORY з WIDGET_CATEGORY_KEYGUARD є сигналом від хоста, а відсутнє значення лишає хост невідомим. [не знайдено] Точні dp і стан прапорця для Pixel Android 17 QPR2. Використовувати системний ресурс радіуса. https://android-developers.googleblog.com/2025/03/widgets-on-lock-screen-faq.html
- На екрані блокування не показуємо приватних даних **[аналіз]**.

### 10.3 Анімація стану в RemoteViews

- `RemoteViews.setCompoundButtonChecked` і `setOnCheckedChangeResponse` (Android 12+) дають справжні `Switch`/`CheckBox` з рідною анімацією перемикання **[AOSP: RemoteViews.java]**. У Glance це `Switch`/`CheckBox` composables.
- `setViewLayoutMargin`, `setColorStateList` змінюють атрибути без повного перезбирання **[docs: appwidgets/enhance]**.
- Довільних spring-анімацій у RemoteViews немає. Зміни стану передаємо зміною кольору, іконки (filled/outlined) і рідними compound buttons **[аналіз]**.

### 10.4 Правило «віджет ніколи не сіріє»

- Фон тільки `widgetBackground` або `primaryContainer`/`secondaryContainer`, ніколи `surface`/`surfaceVariant`.
- Немає даних: показуємо останнє відоме з DataStore тим самим кольором. Пристрій недоступний: іконка змінюється на outlined, акцент лишається.
- Сірий текст на сірому фоні заборонений (див. 13.3).

---

## 11. Іконки, Material Symbols, splash

### 11.1 Іконка апки **[docs: icon_design_adaptive]**

- Шари 108 x 108 dp. Безпечна зона 66 x 66 dp (ніколи не обрізається маскою). Зовнішні 18 dp з кожного боку для маски й ефектів. Логотип від 48 до 66 dp.
- Обов'язково `<monochrome>`. З Android 16 QPR2 система сама тонує апки без нього, але власний шар дає контроль над виглядом.
- [docs] Android 16 QPR2 задокументував системний вибір форми та автотонування. Зберігати власний monochrome і перевіряти іконку під різними масками. [не знайдено] Окрема вимога до градієнтів у стилі Google 2025 для сторонніх застосунків. https://developer.android.com/develop/ui/compose/system/icon_design_adaptive

```xml
<!-- res/mipmap-anydpi-v26/ic_launcher.xml -->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
```

### 11.2 Material Symbols

- `material-icons-extended` Google більше не підтримує і не рекомендує: застарілий вигляд і значно довша збірка **[docs: compose/graphics/images/material]**. Material Symbols замінили Material Icons **[docs: fonts/faq]**.
- Джерело без Android Studio: репозиторій `google/material-design-icons`, шлях `symbols/android/<name>/materialsymbolsrounded/<name>_24px.xml`. Варіанти: `_fill1_24px.xml` (заповнена), `_wght500_24px.xml` (вага) **[перевірено HTTP 200 на bluetooth]**. Viewport 960 x 960.
- Правило: **Rounded**, вага 400. Неактивний стан outlined, активний `fill1` **[аналіз]**. Скрипт скіла качає потрібні іконки в `res/drawable/ms_<name>.xml` і `ms_<name>_fill.xml`.

### 11.3 Splash **[docs: splash-screen]**

- `androidx.core:core-splashscreen:1.2.0`, `installSplashScreen()` до `super.onCreate()`.
- Іконка без фону 288 x 288 dp у колі 192 dp. З фоном 240 x 240 dp у колі 160 dp. Анімована AVD має полотно 432 dp, видимі внутрішні 288 dp.
- Анімація до 1000 мс на телефонах, затримка старту до 166 мс. Фон одним кольором без прозорості. Branding image не радять.
- `windowSplashScreenBehavior = icon_preferred` (Android 13+) гарантує показ анімованої іконки.

---

## 12. Тактильні відгуки й звук

### 12.1 Константи **[AOSP: HapticFeedbackConstants.java; androidx-main: compose/ui/.../HapticFeedbackType.kt]**

| Подія | View | Compose `HapticFeedbackType` |
|---|---|---|
| Успіх, підтвердження | `CONFIRM` (16) | `Confirm` |
| Відмова, помилка | `REJECT` (17) | `Reject` |
| Перемикач увімк. / вимк. | `TOGGLE_ON` (21) / `TOGGLE_OFF` (22) | `ToggleOn` / `ToggleOff` |
| Перебір варіантів, слайдер | `SEGMENT_TICK` (26) / `SEGMENT_FREQUENT_TICK` (27) | `SegmentTick` / `SegmentFrequentTick` |
| Поріг жесту | `GESTURE_THRESHOLD_ACTIVATE` (23) | `GestureThresholdActivate` |
| Кінець жесту | `GESTURE_END` (13) | `GestureEnd` |
| Натиск віртуальної клавіші | `VIRTUAL_KEY` (1) | `VirtualKey` |
| Довгий натиск | `LONG_PRESS` (0) | `LongPress` |
| Секундна стрілка | `CLOCK_TICK` (4) | немає в Compose, тільки через `View` |

- Компоненти material3 **самі не вібрують** (у sources jar `LocalHapticFeedback` використовує лише TimePicker) **[код m3]**. Тактильний відгук додаємо самі.
- [AOSP] Launcher3 WorkspaceTouchListener використовує LONG_PRESS для довгого натиску на робочому столі. SystemUI SeekableSliderHapticPlugin делегує сигнали SliderHapticFeedbackProvider. Це окремі шляхи, тому не варто переносити одну константу на всі слайдери. [не знайдено] Повна матриця викликів Settings/SystemUI і фактичні виклики пропрієтарних апок на Pixel QPR2. https://android.googlesource.com/platform/packages/apps/Launcher3/+/8555fcfcf9/src/com/android/launcher3/touch/WorkspaceTouchListener.java
- `performHapticFeedback` поважає системне налаштування «Touch feedback». Власні `VibrationEffect` його обходять, тому для UI беремо тільки константи **[аналіз]**.

### 12.2 Двоетапний відгук (правило M7)

```kotlin
@Composable
fun rememberTwoStageHaptics(): TwoStageHaptics {
    val h = LocalHapticFeedback.current
    return remember(h) { TwoStageHaptics(h) }
}
class TwoStageHaptics(private val h: HapticFeedback) {
    fun touch() = h.performHapticFeedback(HapticFeedbackType.VirtualKey)          // етап 1: миттєво на дотик [аналіз]
    fun result(ok: Boolean) = h.performHapticFeedback(if (ok) HapticFeedbackType.Confirm else HapticFeedbackType.Reject)
    fun toggled(on: Boolean) = h.performHapticFeedback(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
}

// Використання
onClick = {
    haptics.touch()
    scope.launch {
        val ok = device.setMode(mode)            // чекаємо підтвердження від пристрою
        haptics.result(ok)                       // етап 2: імпульс лише після відповіді
    }
}
```

### 12.3 Звук

Власних звуків не додаємо **[аналіз]**. Якщо звук потрібен для кліку у View, то лише `View.playSoundEffect(SoundEffectConstants.CLICK)`, що поважає системне «Touch sounds».

---

## 13. Доступність як частина дизайну

### 13.1 Шрифт до 200 %

- Android 14+ масштабує шрифт нелінійно до 200 % **[docs: about/versions/14/features]**. Великий текст росте повільніше за дрібний, тому `4.sp + 20.sp` може не дорівнювати `24.sp`. Відступи ніколи не задаємо в `sp`.
- Усі розміри тексту в `sp`, висоти контейнерів з текстом не фіксуємо (`heightIn(min = ...)`, а не `height`).
- Кожен екран рендеримо на `fontScale = 2.0` (розділ 15).

### 13.2 Цілі дотику

- Мінімум 48 x 48 dp. material3 сам резервує 48 dp через `minimumInteractiveComponentSize()` (`LocalMinimumInteractiveComponentSize`) **[код m3: InteractiveComponentSize.kt]**. Для кастомних клікабельних елементів додаємо цей модифікатор вручну.

### 13.3 Контраст

- Текст 4.5:1, великий текст і значущі іконки 3:1 (WCAG AA) **[docs]**. Пари `surface*` + `on*` з dynamic color цю вимогу проходять. Ламають її кастомні альфи й «сірий» колір.
- На API 34+ dynamic scheme бере кольори з системи, тому й системне налаштування контрасту працює автоматично **[код m3: dynamicLightColorScheme34]**.

### 13.4 TalkBack на кастомних елементах

```kotlin
// Форма-статус: значення голосом, а не «image»
Modifier.semantics {
    contentDescription = "Noise control"
    stateDescription = if (connected) "Connected" else "Searching"
    liveRegion = LiveRegionMode.Polite   // озвучує зміну статусу
}
// Кастомний перемикач
Modifier.toggleable(value = on, role = Role.Switch, onValueChange = onChange)
// Група режимів: Role.RadioButton на кожній кнопці + selectableGroup() на контейнері (див. 6.3)
```

ATF-перевірки на емуляторі ловлять відсутні підписи, малі цілі й низький контраст.

---

## 14. Обов'язкові правила (досвід Earbuds і VPN)

| # | Правило | Як реалізувати | Як перевірити |
|---|---|---|---|
| M1 | Тільки справжні компоненти M3E, жодних саморобних імітацій | Розділ 6. `MaterialExpressiveTheme`. Списки лише `SegmentedListItem`, режими лише connected `ToggleButton` | Grep у CI: заборонені `RoundedCornerShape(` у файлах екранів поза `theme/`, `Card(` як імітація групи, `MaterialTheme(` замість `MaterialExpressiveTheme(` |
| M2 | Віджети ніколи не сіріють, фон з палітри шпалер | `GlanceTheme.colors.widgetBackground` / `primaryContainer`; останнє відоме значення | Glance-рендер усіх станів (світла, темна, «немає даних») + еталон |
| M3 | Жодних стрибків верстки і «Reading…» | DataStore-кеш, той самий розмір плейсхолдера, `tnum` | Grep рядка `Reading` / `Loading…`; скриншот першого кадру = кадру після оновлення за розміром |
| M4 | Жодного сірого тексту на сірому | Лише `on*`-ролі; альфа 0.38 тільки для disabled | ATF contrast check; grep `Color.Gray`, `alpha =` у Text-кольорах |
| M5 | Статус формою, кольором, рухом, а не текстом | `StatusBlob` (3.2), колір за 5.2, анімація лише при зміні | Ревʼю скриншотів: статус читається без тексту |
| M6 | Цифри, що змінюються, прокручуються | `RollingNumber` (9.1) | Unit-тест: зміна значення запускає `AnimatedContent`; скриншот проміжного кадру |
| M7 | Двоетапний тактильний відгук | `TwoStageHaptics` (12.2) | Unit-тест з фейковим `HapticFeedback`: спершу `VirtualKey`, `Confirm`/`Reject` лише після відповіді |
| M8 | Перед кожним релізом рендер кожного екрана й стану (світла/темна, шрифт 200 %) і порівняння з еталоном | Roborazzi-матриця: екран x стан x тема x fontScale {1.0, 2.0} | CI падає при diff; оновлення еталонів лише окремим комітом |

---

## 15. Чекліст перед релізом

- [ ] `MaterialExpressiveTheme`, dynamic color, Google Sans Flex підключені.
- [ ] Немає `MaterialTheme(` у коді екранів, немає `material-icons-extended` у залежностях.
- [ ] Усі списки `SegmentedListItem` + `segmentedShapes(index, count)` + gap 2 dp.
- [ ] Hero-значення використовує emphasized-стиль і `tnum`.
- [ ] Рендери: кожен екран x кожен стан x {light, dark} x {fontScale 1.0, 2.0} збігаються з еталонами.
- [ ] ATF на емуляторі без помилок (контраст, цілі 48 dp, підписи).
- [ ] Після холодного старту в спокої немає анімацій, що крутяться; у фоні немає сервісів і wake lock.
- [ ] Predictive back перевірено жестом з обох країв (якщо є кілька екранів).
- [ ] Віджет: рендери всіх розмірів і станів, світла й темна, `widgetBackground`, системний радіус, `appWidgetBackground()`, generated preview.
- [ ] Іконка: adaptive + monochrome, логотип у межах 66 dp; splash до 1000 мс.
- [ ] Тактильні відгуки на кожній дії; двоетапні там, де є пристрій.
- [ ] Перший кадр показує останнє відоме значення, без «Reading…».

## 16. Антипатерни, що видають «не-Google» апку

1. `MaterialTheme` замість `MaterialExpressiveTheme`: рух без відскоку, «старий» M3.
2. Картки з тінню замість груп `surfaceContainer` з gap 2 dp.
3. Однакові кути в усіх рядків групи, або кути 8/12 dp «на око».
4. `Color.Gray`, `Color.LightGray`, альфа на тексті, сірий текст на сірому.
5. Спінер, «Loading…», «Reading…» на місці значення, яке вже колись було відоме.
6. Стрибок висоти блоку, коли приходять дані; нетабличні цифри, що «дихають».
7. Пульсуючі й нескінченні анімації в спокої.
8. `tween(300)` з `FastOutSlowInEasing` замість пружин `motionScheme`.
9. Іконки `material-icons-extended` (старий стиль) замість Material Symbols Rounded.
10. Віджет із сірим фоном, захардкодженим радіусом 16 dp, без dark-теми; «No data» сірим текстом.
11. Статус лише текстом («Connected»), без форми й кольору.
12. Тактильний відгук відсутній, або одна й та сама вібрація на все.
13. Перехід між екранами crossfade 700 мс (дефолт Navigation 3).
14. Іконка апки без monochrome-шару, логотип виходить за 66 dp.
15. Фіксовані висоти рядків, що обрізають текст на 200 %.

## 17. Результат вузького ресерчу №2

1. [не знайдено] Точні макети шести Pixel-апок. Див. 7.3.
2. [docs] Підтверджено adaptive/monochrome, вибір маски й автотонування. Див. 11.1.
3. [docs] Орієнтир слота 4 x 3. [AOSP] Оголошення use_smaller_app_widget_system_radius описує 24 замість 28 dp. [не знайдено] Стан на конкретній збірці Pixel. https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/appwidget/flags.aconfig
4. [AOSP] Підтверджено окремі шляхи Launcher3 і SystemUI. Див. 12.1.
5. [код] ROND типово 0 у вихідному шрифті. [не знайдено] Параметри прошивки. Див. 1.2.

## 18. Джерела

- Google Maven: `androidx/compose/material3/material3-android/1.5.0-rc01/material3-android-1.5.0-rc01-sources.jar` (токени: ExpressiveMotionTokens, StandardMotionTokens, MotionTokens, ShapeTokens, TypeScaleTokens, ColorLightTokens, ColorDarkTokens, ListTokens, ButtonGroupSmallTokens, ConnectedButtonGroupSmallTokens, AppBarLargeFlexibleTokens, LoadingIndicatorTokens; файли MaterialTheme.kt, ColorScheme.kt, ListItem.kt, ListItemDefaults.kt, ButtonGroup.kt, MaterialShapes.kt, LoadingIndicator.kt, DynamicTonalPalette.android.kt, InteractiveComponentSize.kt)
- github.com/androidx/androidx (androidx-main): `compose/material3/material3/samples/...` (ButtonGroupSamples, ListSamples, AppBarSamples, ThemeSamples, ProgressIndicatorSamples, LoadingIndicatorSamples, FloatingActionButtonMenuSamples), `navigation3/navigation3-ui/src/androidMain/.../NavDisplay.android.kt`, `navigationevent/.../NavigationEvent.kt`, `compose/animation/.../SharedTransitionScope.kt`, `compose/ui/.../hapticfeedback/HapticFeedbackType.kt`, `glance/glance/src/main/res/values-v31/colors.xml`, `values-night-v31/colors.xml`
- android.googlesource.com/platform/frameworks/base (main): `libs/WindowManager/Shell/src/com/android/wm/shell/back/CrossActivityBackAnimation.kt`, `DefaultCrossActivityBackAnimation.kt`, `libs/WindowManager/Shell/shared/.../animation/Interpolators.java`, `libs/WindowManager/Shell/res/values/dimen.xml`, `core/java/android/view/animation/BackGestureInterpolator.java`, `core/java/android/view/HapticFeedbackConstants.java`, `core/java/android/widget/RemoteViews.java`, `core/res/res/values/dimens.xml`
- github.com/material-components/material-components-android (master): `transition/MaterialSharedAxis.java`, `FadeThroughProvider.java`, `MaterialContainerTransform.java`, `transition/res/values/dimens.xml`
- github.com/google/fonts: `ofl/googlesansflex/METADATA.pb`, `ofl/googlesans/METADATA.pb`
- github.com/google/material-design-icons: `symbols/android/...`
- developer.android.com: appwidgets/layouts, appwidgets/enhance, design/ui/mobile/guides/widgets (layouts, sizing, style), compose/glance/generated-previews, launch/icon_design_adaptive, launch/splash-screen, navigation-3/animate-destinations, compose/graphics/images/material
- developers.google.com/fonts/faq
- android-developers.googleblog.com: Widgets on lock screen FAQ (березень 2025)
- Медіа про хаб на екрані блокування: Android Authority, Droid Life (серпень і грудень 2025)
