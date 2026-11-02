# طراحی و قرارداد رفتاری کانکتور Stalwart SCIM — نسخه 2.0.4

## وضعیت سند

این سند قرارداد فنی و رفتاری نسخه 2.0.4 را تعریف می‌کند. شاخه توسعه از نسخه 2.0.3 منشعب شده و سیاست عدم همگام‌سازی ایمیل اصلی، Aliasها و username داخلی WSO2 را صریح می‌کند. نسخه Maven در این شاخه 2.0.4-SNAPSHOT است.

موفقیت آزمون‌های CI فقط صحت Build و آزمون‌های خودکار موجود را نشان می‌دهد. فعال‌سازی OSGi، رویدادهای واقعی WSO2 IS 7.3.0، مسیر CSV import، دیتابیس عملیاتی چندگرهی و ارتباط زنده با Stalwart باید جداگانه آزمایش شوند.

## ۱. اصول طراحی

1. externalId شناسه پایدار اتصال WSO2 و Stalwart است.
2. userName در Stalwart فقط در زمان ایجاد حساب از Claim ایمیل اصلی گرفته می‌شود.
3. userName و externalId در هیچ عملیات Update تغییر داده نمی‌شوند.
4. تغییر ایمیل اصلی، Alias، افزودن/حذف Alias و تغییر username داخلی WSO2 هیچ درخواست SCIM و هیچ ورودی صف ایجاد نمی‌کند.
5. تنها تغییرات مجاز نام، وضعیت حساب، عضویت گروه توزیع و حذف کاربر به Stalwart منتقل می‌شوند.
6. گروه Email یک گروه مجوزدهی در WSO2 است و در Stalwart به‌عنوان Group SCIM ساخته یا نگهداری نمی‌شود؛ این گروه فقط active را کنترل می‌کند.
7. تغییرات نام در HYBRID از صف پایدار JDBC و Bulk چندعملیاتی عبور می‌کنند؛ سایر عملیات در همان لحظه و به‌صورت مستقل انجام می‌شوند.
8. رمز عبور و Claimهای نامرتبط ارسال نمی‌شوند.

## ۲. حالت‌های انتقال

| حالت | ساخت کاربر | تغییر نام | active | عضویت گروه توزیع | حذف |
|---|---|---|---|---|---|
| INDIVIDUAL | POST فوری | PATCH فوری | PATCH فوری | PATCH فوری | DELETE فوری |
| BULK | Bulk تک‌عملیاتی فوری | Bulk تک‌عملیاتی PATCH | Bulk تک‌عملیاتی PATCH | Bulk تک‌عملیاتی PATCH | Bulk تک‌عملیاتی DELETE |
| HYBRID | POST مستقل فوری | Outbox و Bulk چندعملیاتی هر ۵ دقیقه | PATCH مستقل فوری | PATCH مستقل فوری | DELETE مستقل فوری |

BULK در این نسخه انتقال هم‌زمان یک عملیات از طریق Bulk endpoint است و به‌معنای صف‌سازی بین رویدادهای مستقل WSO2 نیست. HYBRID تنها حالت دارای صف و تجمیع چند رویداد است.

## ۳. جدول قرارداد نهایی رویدادها

| رویداد WSO2 | عملیات Stalwart | زمان/روش | صف HYBRID |
|---|---|---|---|
| ایجاد کاربر | POST User با userName اولیه و externalId پایدار | فوری؛ INDIVIDUAL در INDIVIDUAL/HYBRID و Bulk تک‌عملیاتی در BULK | خیر |
| تغییر FIRSTNAME | PATCH displayName | فوری در INDIVIDUAL/BULK؛ دسته‌ای هر ۵ دقیقه در HYBRID | بله |
| تغییر LASTNAME | PATCH displayName | فوری در INDIVIDUAL/BULK؛ دسته‌ای هر ۵ دقیقه در HYBRID | بله |
| تغییر DISPLAYNAME | PATCH displayName | فوری در INDIVIDUAL/BULK؛ دسته‌ای هر ۵ دقیقه در HYBRID | بله |
| تغییر آدرس اصلی ایمیل | هیچ | هیچ درخواست SCIM | خیر |
| تغییر Alias | هیچ | هیچ درخواست SCIM | خیر |
| افزودن Alias | هیچ | هیچ درخواست SCIM | خیر |
| حذف Alias | هیچ | هیچ درخواست SCIM | خیر |
| تغییر username داخلی WSO2 | هیچ | هیچ درخواست SCIM | خیر |
| افزودن به گروه Email | PATCH active=true | فوری | خیر |
| حذف از گروه Email | PATCH active=false | فوری | خیر |
| تغییر Claim فعال | PATCH active | فوری | خیر |
| افزودن عضویت گروه توزیع | PATCH Group.members با add | فوری | خیر |
| حذف عضویت گروه توزیع | PATCH Group.members با remove | فوری | خیر |
| تغییر خود گروه WSO2 | هیچ | هیچ درخواست گروهی | خیر |
| حذف کاربر | DELETE User | فوری | خیر |
| تغییر رمز عبور | هیچ | هیچ درخواست SCIM | خیر |

در حالت BULK، عملیات فوری بالا در قالب Bulk تک‌عملیاتی ارسال می‌شوند. در حالت HYBRID، فقط سه ردیف تغییر نام در صف قرار می‌گیرند.

### تضمین no-op برای رویدادهای هویتی و ایمیل

در مسیر UPDATE، پیش از lookup هر منبع، کانکتور بررسی می‌کند که آیا Claim تغییر‌یافته در محدوده تغییرات پشتیبانی‌شده قرار دارد یا خیر. اگر تنها Claimهای ایمیل اصلی، Alias، username داخلی یا Claimهای نامرتبط در رویداد باشند، مسیر بدون GET/POST/PATCH/DELETE خاتمه می‌یابد.

Aliasها در این Connector نگاشت یا پشتیبانی نمی‌شوند؛ بنابراین شکل Claim مربوط به Alias نیز نباید موجب عملیات SCIM شود. در Update هیچ مسیر جایگزینی برای تغییر userName یا externalId وجود ندارد.

## ۴. منطق نام نمایشی

شرط تغییر نام OR است:

FIRSTNAME changed OR LASTNAME changed OR DISPLAYNAME changed

- اگر DISPLAYNAME خود تغییر کرده باشد، مقدار جاری آن انتخاب می‌شود.
- اگر FIRSTNAME یا LASTNAME تغییر کرده باشد، هر دو مقدار از تصویر کامل و جاری UserStore خوانده و ترکیب می‌شوند.
- در صورت نبودن هر یک از Claimهای لازم در تصویر کامل، تغییر ناقص ارسال نمی‌شود.
- اگر مقدار نهایی خالی باشد، جایگزینی مخرب ارسال نمی‌شود.
- در HYBRID تغییرات تکراری برای یک scope و externalId ادغام می‌شوند؛ مقدار نهایی آخرین snapshot معتبر است.
- تغییر نام و وضعیت active یا عضویت گروه در یک bucket مشترک قرار نمی‌گیرند.

## ۵. قرارداد SCIM User

### ایجاد

POST /Users فقط شامل این داده‌ها است:
- SCIM core User schema؛
- userName معتبر و کامل ایمیل؛
- externalId پایدار و غیرخالی؛
- displayName در صورت وجود مقدار معتبر؛
- active فقط در صورت پیکربندی Claim فعال معتبر.

ایجاد شامل emails، password، phoneNumbers، addresses، photos، groups، Claimهای دلخواه، meta یا شناسه سمت سرور نیست.

### تغییر پروفایل

PATCH /Users/{id} فقط برای displayName است. userName، externalId، emails، active، groups و password در payload تغییر پروفایل قرار نمی‌گیرند.

PATCH فقط برای Claimهای تغییرکرده مجاز ساخته می‌شود؛ سند Operations خالی ارسال نمی‌شود.

### وضعیت حساب

گروه WSO2 با نام پیکربندی‌شده Email به active نگاشت می‌شود. حذف از گروه Email به active=false و افزودن به آن به active=true تبدیل می‌شود. گروه Email هرگز در endpoint گروه‌های Stalwart جست‌وجو یا ساخته نمی‌شود.

### گروه‌های توزیع

گروه‌های دیگر باید از قبل در Stalwart موجود باشند. عضویت با PATCH delta روی Group اعمال می‌شود. افزودن/حذف عضو با بازنویسی فهرست کامل اعضا انجام نمی‌شود. رویدادهای چرخه عمر خود گروه WSO2 نادیده گرفته می‌شوند.

## ۶. Outbox پایدار HYBRID

جدول: STALWART_SCIM_PROFILE_OUTBOX

- منبع حقیقت صف، JDBC است و نه حافظه فرایند.
- درج/ادغام با تراکنش انجام می‌شود.
- شناسه کلید شامل scope و externalId است.
- scope مقصد، سازمان (در صورت وجود)، tenant و user store را جدا می‌کند.
- claim کردن ردیف‌ها با قفل ردیفی و lease انجام می‌شود.
- وضعیت‌ها PENDING، IN_FLIGHT، SENT و DEAD هستند.
- تلاش مجدد محدود و backoff دارد؛ پس از آستانه تعریف‌شده، رکورد DEAD می‌شود.
- رکوردهای SENT برای ۳۰ روز نگهداری می‌شوند؛ DEAD برای بررسی اپراتور حفظ می‌شود.
- توکن و رمز عبور ذخیره نمی‌شوند.
- همه نودهای WSO2 باید از دیتابیس مشترک استفاده کنند.

worker هر پنج دقیقه اجرا و در هر نوبت حداکثر ۲۵ ردیف را claim می‌کند. شناسه مقصد باید با JVM property مربوطه یکسان باشد. مقدار JNDI کانکتور و Event Handler باید یکسان باشد.

## ۷. قرارداد Bulk چندعملیاتی

- هر ردیف صف به یک PATCH مستقل روی User موجود تبدیل می‌شود.
- bulkId برای PATCH مستقل و بدون ارجاع متقابل لازم نیست.
- محدودیت اندازه payload و تعداد عملیات پیش از ارسال بررسی می‌شود.
- وضعیت هر عملیات در BulkResponse جداگانه تحلیل می‌شود.
- فقط عملیات موفق SENT می‌شوند.
- خطای یک عملیات نباید باعث تأیید یا replay عملیات موفق دیگر شود.
- نتیجه مبهم شبکه با خواندن مجدد displayName هر کاربر reconcile می‌شود.
- fallback فقط برای عدم پشتیبانی قطعی Bulk endpoint (404/405/501) مجاز است.
- خطای HTTP کلی موفق به‌تنهایی معیار موفقیت عملیات‌ها نیست.

## ۸. تنظیمات WSO2 و JVM

تنظیمات مهم Connector:
- provisioning-mode: INDIVIDUAL، BULK یا HYBRID
- enable-profile-updates: در HYBRID باید true باشد
- hybrid-outbox-jndi-name: به‌طور پیش‌فرض jdbc/WSO2CarbonDB
- hybrid-scope-id: targetId|tenantDomain|userStoreDomain یا targetId|organizationId|tenantDomain|userStoreDomain
- email-membership-group: به‌طور پیش‌فرض Email

JVM properties موردنیاز HYBRID:
- stalwart.scim.hybrid.enabled=true
- stalwart.scim.hybrid.outbox.jndi=jdbc/WSO2CarbonDB
- stalwart.scim.hybrid.target-id=<unique-target-id>

Event Handler باید به POST_ADD_USER و POST_SET_USER_CLAIMS مشترک شود. Handler برای رویداد تغییر Claim، تنها زمانی Outbox را می‌نویسد که یکی از Claimهای نام تغییر کرده باشد.

URIهای سفارشی externalId، given name، family name و displayName باید در JVM و Connector یکسان باشند؛ عدم تطابق باید initialization را متوقف کند.

## ۹. امنیت و حریم داده

- Bearer Token و password در Outbox یا log ذخیره نمی‌شوند.
- مقادیر Claimها و کل رکورد کاربر در log چاپ نمی‌شوند.
- HTTPS و اعتبارسنجی گواهی TLS لازم است.
- فیلتر SCIM و segmentهای URL باید escape/encode شوند.
- cache محدود به endpoint و نوع resource است.
- درخواست‌های هم‌زمان محدود می‌شوند.
- externalId معیار authoritative است؛ تطبیق صرف با ایمیل مجاز نیست.

## ۱۰. تست‌های خودکار مورد انتظار

- تست factory و تعداد ۲۱ فیلد UI؛
- بررسی انگلیسی بودن توضیحات UI؛
- no-op برای ایمیل اصلی، Alias، افزودن/حذف Alias و username داخلی؛
- تست ساخت کاربر و reconcile externalId؛
- تست OR برای نام‌ها و اولویت DISPLAYNAME؛
- تست coalescing و lease صف؛
- تست ارسال چند PATCH در Bulk؛
- تست acknowledgement مستقل عملیات؛
- تست retry/dead-letter و نتیجه مبهم؛
- تست active و عضویت گروه.

## ۱۱. آزمون‌های عملیاتی اجباری پیش از انتشار

این موارد با Smoke Test یا CI به‌تنهایی اثبات نمی‌شوند:
1. نصب دو bundle روی توزیع دقیق WSO2 IS 7.3.0 و بررسی OSGi resolution؛
2. تحویل واقعی POST_SET_USER_CLAIMS و POST_ADD_USER؛
3. بررسی اینکه CSV import چه رویدادهایی تولید می‌کند و آیا outbound provisioning دوباره همان تغییر را ارسال می‌کند؛
4. تست دیتابیس واقعی WSO2CarbonDB و dialect آن؛
5. تست restart، بازیابی lease و چندنودی؛
6. تست Stalwart Enterprise واقعی، مجوزها، domain/tenant، PATCH، Bulk و ETag؛
7. تست خطاهای جزئی Bulk و قطع شبکه پس از commit احتمالی.

تا تکمیل این موارد، HYBRID و نسخه 2.0.4 باید توسعه‌ای تلقی شوند و نه انتشار تولیدی تأییدشده.

## ۱۲. نکته انتشار

نسخه Maven در شاخه توسعه 2.0.4-SNAPSHOT است. پس از موفقیت CI و تکمیل آزمون‌های واقعی، می‌توان آن را به نسخه release بدون پسوند SNAPSHOT تبدیل و tag نهایی ایجاد کرد. شاخه release/2.0.1 نباید در این کار تغییر کند.
