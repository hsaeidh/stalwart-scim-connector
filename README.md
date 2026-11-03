# کانکتور Provisioning بین WSO2 Identity Server و Stalwart SCIM

## ۱. وضعیت نسخه و دامنهٔ پشتیبانی

- نسخهٔ توسعه: **2.0.8-SNAPSHOT**
- پلتفرم هدف: **WSO2 Identity Server 7.3.0**
- نسخهٔ Java موردنیاز: **Java 21**
- مقصد Provisioning: **Stalwart Enterprise 1.0.2** با SCIM 2.0 فعال
- نوع کانکتور: Outbound Provisioning Connector
- پروتکل: SCIM 2.0 با Bearer Token و TLS

این شاخه شامل اصلاحات ممیزی نسخهٔ 2.0.7 است. موفقیت Build و تست‌های محلی به‌تنهایی به معنی تأیید Production نیست. تا زمانی که JAR روی نمونهٔ واقعی WSO2 IS 7.3.0 نصب و با Stalwart Enterprise 1.0.2 آزمون end-to-end نشده، وضعیت آن را **آمادهٔ آزمون پذیرش** در نظر بگیرید، نه Production-certified.

## ۲. هدف و اصول طراحی

این پروژه WSO2 را به‌عنوان مرجع هویت و مجوز دسترسی به ایمیل نگه می‌دارد و حساب‌های کاربری را از طریق SCIM در Stalwart ایجاد و به‌روزرسانی می‌کند.

اصول ثابت طراحی:

1. **بدون Event Handler جداگانه:** رویدادهای Provisioning از مسیر استاندارد Outbound Connector در WSO2 دریافت می‌شوند.
2. **بدون JVM Property یا Environment Variable اختصاصی:** تنظیمات از UI کانکتور و در صورت نیاز از فایل TOML خوانده می‌شوند. برای کشف مسیر TOML از `carbon.home` استاندارد WSO2 استفاده می‌شود.
3. **بدون زیرساخت جانبی:** DataSource، JNDI، جدول اختصاصی، صف پایدار، Outbox، سرویس واسط یا اسکریپت زمان‌بندی‌شده لازم نیست.
4. **بدون Provisioning گروه در Stalwart:** گروه‌های WSO2 فقط برای تصمیم‌گیری دسترسی ایمیل استفاده می‌شوند. هیچ Group در Stalwart ایجاد، جست‌وجو، ویرایش یا حذف نمی‌شود.
5. **حذف نرم حساب:** حذف کاربر در WSO2 به SCIM PATCH با `active=false` تبدیل می‌شود؛ کانکتور برای حذف کاربر از SCIM DELETE استفاده نمی‌کند.
6. **شناسهٔ پایدار:** `externalId` در Stalwart شناسهٔ پایدار WSO2 است. `userName` مقصد، ایمیل اصلی کاربر در زمان ایجاد است.
7. **عدم تغییر کلیدهای هویتی:** پس از ایجاد، `userName` و `externalId` در Update تغییر نمی‌کنند. تغییر ایمیل، Alias یا username داخلی WSO2 باعث تغییر حساب مقصد نمی‌شود.
8. **Fail-closed:** هر جا عضویت در گروه Email یا دادهٔ هویتی ضروری قابل اثبات نباشد، کانکتور حساب را فعال نمی‌کند و در موارد لازم Provisioning را با خطا متوقف می‌کند.
9. **عدم ادعای exactly-once:** Bulk فقط در حافظه تجمیع می‌شود. در صورت Crash یا نتیجهٔ مبهم شبکه، تحویل پایدار و exactly-once تضمین نمی‌شود.

## ۳. معماری و جریان کلی

```text
WSO2 Identity Server 7.3.0
  |
  | Outbound Provisioning Event
  v
Stalwart SCIM Connector (OSGi Bundle)
  |-- Claim mapping / UserStore snapshot
  |-- Email-group authorization gate
  |-- Immutable externalId lookup
  |-- Individual HTTP client OR in-memory Bulk dispatcher
  |
  | HTTPS + Bearer Token + SCIM 2.0
  v
Stalwart Enterprise
  |-- /scim/v2/Users
  |-- /scim/v2/Users/.search
  |-- /scim/v2/Bulk (when enabled/supported)
  |
  X  No Stalwart Group provisioning
```

آدرس پایه باید به مسیر `/scim/v2` ختم شود؛ برای نمونه:

```text
https://mail.example.net/scim/v2
```

آدرس پایه را به `/Users`، `/Groups` یا `/Bulk` ختم نکنید. کانکتور مسیرهای لازم را به آدرس پایه اضافه می‌کند.

## ۴. قرارداد هویتی و نگاشت Claimها

نگاشت پیش‌فرض:

| مفهوم | Claim URI پیش‌فرض | ویژگی مقصد | زمان استفاده |
|---|---|---|---|
| ایمیل اصلی | `http://wso2.org/claims/emailaddress` | `userName` | فقط زمان ایجاد |
| شناسهٔ پایدار WSO2 | `http://wso2.org/claims/username` | `externalId` | ایجاد و تطبیق حساب |
| نام | `http://wso2.org/claims/givenname` | ورودی `displayName` | ایجاد و Update |
| نام خانوادگی | `http://wso2.org/claims/lastname` | ورودی `displayName` | ایجاد و Update |
| نام نمایشی | `http://wso2.org/claims/displayname` | `displayName` | ایجاد و Update |
| وضعیت حساب | Claim بولی اختیاری و قابل تنظیم | `active` | مجوز تکمیلی برای غیرفعال‌سازی |
| عضویت ایمیل | گروه WSO2 با نام پیش‌فرض `Email` | `active` | شرط اصلی فعال‌بودن حساب |

### ۴.۱. تفاوت userName و externalId

- `userName` مقصد باید یک ایمیل کامل مانند `person@example.net` باشد؛ username بدون دامنه پذیرفته نمی‌شود.
- `externalId` شناسهٔ پایدار کاربر در WSO2 است و باید در طول عمر حساب ثابت بماند.
- این دو مقدار دو نقش مستقل دارند و نباید به‌جای یکدیگر استفاده شوند.
- در Update فقط با `externalId` حساب مقصد پیدا می‌شود. جست‌وجو با ایمیل برای تصاحب یا جایگزین‌کردن حساب موجود انجام نمی‌شود.
- اگر `externalId` با یک حساب دیگر تطبیق نکند ولی `userName` از قبل متعلق به شناسهٔ دیگری باشد، کانکتور آن حساب را تصاحب نمی‌کند و خطا می‌دهد.
- تغییر ایمیل اصلی در WSO2، ایمیل حساب موجود در Stalwart را تغییر نمی‌دهد. تغییر ایمیل باید به‌عنوان فرآیند مستقل و کنترل‌شده مدیریت شود؛ این کانکتور عمداً چنین تغییری را خودکار انجام نمی‌دهد.

### ۴.۲. نام نمایشی و به‌روزرسانی OR

در زمان ایجاد، اولویت با Claim نام نمایشی است. اگر این Claim موجود نباشد، نام و نام خانوادگی برای ساخت `displayName` ترکیب می‌شوند.

در زمان Update، شرط ارسال پروفایل **OR** است:

- تغییر FIRSTNAME به‌تنهایی؛ یا
- تغییر LASTNAME به‌تنهایی؛ یا
- تغییر DISPLAYNAME به‌تنهایی؛

هر کدام به‌تنهایی برای ایجاد درخواست Update کافی است.

اگر فقط FIRSTNAME یا LASTNAME در رویداد آمده باشد، کانکتور مقادیر کامل و جاری FIRSTNAME و LASTNAME را از UserStore مربوط به Tenant جاری WSO2 می‌خواند و سپس `displayName` کامل را می‌سازد. بنابراین مقدار ناقص یا قدیمی Claim رویداد به‌عنوان نام کامل ارسال نمی‌شود. خالی‌شدن مقدار نهایی می‌تواند باعث پاک‌شدن `displayName` در Stalwart شود.

تغییر ایمیل اصلی، Alias، username داخلی WSO2 و Claimهای نامرتبط، به‌تنهایی Update در Stalwart ایجاد نمی‌کند.

## ۵. سیاست دسترسی: گروه Email در WSO2

گروه WSO2 با نام قابل تنظیم `email-membership-group` (پیش‌فرض `Email`) شرط اصلی دسترسی به ایمیل است. نام گروه با حذف پیشوند User Store مقایسه می‌شود و مقایسهٔ نام بدون حساسیت به بزرگی/کوچکی حروف است.

**قانون نهایی فعال‌بودن حساب:**

```text
Stalwart active =
    عضو بودن قطعی در گروه Email
    AND
    مجاز بودن Active Claim (اگر تنظیم شده باشد)
```

Active Claim صرفاً یک قید تکمیلی است؛ مقدار true آن بدون عضویت در گروه Email هرگز مجوز فعال‌سازی صادر نمی‌کند.

### ۵.۱. جدول تصمیم گروه و Active Claim

| وضعیت گروه Email | Active Claim | نتیجهٔ مورد انتظار |
|---|---|---|
| عضو است | true یا تنظیم نشده | فعال |
| عضو است | false | غیرفعال |
| عضو نیست | true | غیرفعال |
| عضو نیست | false | غیرفعال |
| گروه در رویداد نامشخص است | true | به‌تنهایی فعال نمی‌شود |
| گروه در رویداد نامشخص است | false | در صورت وجود حساب، غیرفعال می‌شود |
| Active Claim تنظیم شده ولی مقدار آن مفقود/نامعتبر است | هر وضعیت نیازمند ارزیابی Claim | خطا و عدم فعال‌سازی |

اگر Active Claim تنظیم شده باشد ولی در رویداد مقدار نداشته باشد، کانکتور مقدار جاری آن را از UserStore می‌خواند. نبودن Claim یا مقدار غیر Boolean باعث خطا می‌شود؛ مقدار مفقود به‌صورت خودکار true فرض نمی‌شود. گزینهٔ `invert-active-claim` برای Claimهایی است که معنای معکوس دارند.

### ۵.۲. Snapshot و Delta گروه

رویدادهای WSO2 ممکن است فهرست کامل گروه‌های جاری (Snapshot) و نیز گروه‌های اضافه/حذف‌شده (Delta) را هم‌زمان داشته باشند.

- اگر Snapshot کامل موجود باشد، **فقط Snapshot مرجع نهایی تصمیم است**.
- اگر Snapshot شامل Email نباشد، Delta مثبت و متناقضِ افزودن Email نادیده گرفته می‌شود.
- اگر Snapshot موجود نباشد، Delta مثبت `NEW_GROUP` می‌تواند عضویت را اثبات کند؛ Delta حذف Email دسترسی را لغو می‌کند.
- اگر نه Snapshot و نه Delta مثبت وجود داشته باشد، دسترسی اعطا نمی‌شود.
- Snapshot خالی با Claim گروهِ غایب یکسان نیست: Snapshot خالی یعنی کاربر عضو هیچ گروهی نیست؛ Claim غایب یعنی وضعیت گروه از آن رویداد قابل نتیجه‌گیری نیست.

### ۵.۳. سناریوی ایجاد از رویداد افزودن به گروه

گاهی رویداد افزودن کاربر به گروه، Claim ایمیل یا نام را در Attributeهای Outbound خود ندارد. در این حالت کانکتور برای ایجاد حساب جدید، Claimهای لازم را از UserStore همان Tenant می‌خواند:

- ایمیل اصلی برای `userName`؛
- شناسهٔ پایدار از رویداد/نام کاربری پایدار WSO2 برای `externalId`؛
- FIRSTNAME، LASTNAME و DISPLAYNAME برای پروفایل؛
- Active Claim، در صورت فعال‌بودن تنظیم آن.

اگر ایمیل یا شناسهٔ پایدار قابل بازیابی نباشد، ساخت حساب انجام نمی‌شود. اگر Snapshot کامل گروه با Delta تناقض داشته باشد، Snapshot اولویت دارد و از ایجاد حساب فعال جلوگیری می‌شود.

## ۶. تشریح کامل چرخهٔ عمر کاربر

### سناریو A — ساخت کاربر در WSO2 با عضویت Email

1. WSO2 رویداد ایجاد کاربر را به کانکتور می‌فرستد.
2. کانکتور شناسهٔ پایدار WSO2 را استخراج می‌کند.
3. عضویت در گروه Email از Snapshot موجود بررسی می‌شود.
4. Claim ایمیل اعتبارسنجی می‌شود و باید ایمیل کامل باشد.
5. کانکتور با SCIM `POST /Users` حساب را ایجاد می‌کند.
6. مقدار `userName` برابر ایمیل زمان ایجاد و `externalId` برابر شناسهٔ پایدار WSO2 قرار می‌گیرد.
7. اگر کاربر عضو Email باشد و Active Claim نیز اجازه دهد، `active=true`؛ در غیر این صورت `active=false`.
8. شناسهٔ SCIM برگشتی برای تطبیق‌های بعدی Cache می‌شود.

نمونهٔ مفهومی Body (مقادیر صرفاً نمونه‌اند):

```json
{
  "schemas": ["urn:ietf:params:scim:schemas:core:2.0:User"],
  "userName": "person@example.net",
  "externalId": "wso2-stable-user-id",
  "displayName": "First Last",
  "active": true
}
```

### سناریو B — ساخت کاربر بدون عضویت Email

- حساب می‌تواند برای همگام‌سازی هویت ایجاد شود، اما با `active=false`.
- حتی اگر Active Claim مقدار true داشته باشد، این Claim به‌تنهایی حساب را فعال نمی‌کند.
- با اضافه‌شدن بعدی کاربر به گروه Email، سناریوی فعال‌سازی مجدد اجرا می‌شود.

### سناریو C — افزودن کاربر موجود به گروه Email

1. WSO2 رویداد تغییر عضویت را ارسال می‌کند.
2. کانکتور ابتدا Snapshot کامل گروه را بررسی می‌کند؛ در صورت نبود آن از Delta استفاده می‌کند.
3. حساب مقصد با `externalId` جست‌وجو می‌شود.
4. اگر حساب موجود باشد، وضعیت دسترسی به‌روزرسانی می‌شود.
5. اگر حساب وجود نداشته باشد، Claimهای ضروری از UserStore خوانده و حساب ساخته می‌شود.
6. فعال‌سازی فقط پس از اثبات عضویت Email و تأیید Active Claim (در صورت تنظیم) انجام می‌شود.
7. ایجاد تکراری در اثر Race یا پاسخ 409 با تطبیق `externalId` بررسی می‌شود؛ حسابی با externalId متفاوت تصاحب نمی‌شود.

### سناریو D — حذف کاربر از گروه Email

- حساب Stalwart حذف فیزیکی نمی‌شود.
- کانکتور برای حساب موجود SCIM PATCH با `active=false` ارسال می‌کند.
- این تغییر دسترسی به ایمیل را لغو می‌کند، اما داده‌ها و شناسهٔ حساب را حفظ می‌کند.
- اگر کاربر قبلاً در Stalwart وجود نداشته باشد، حساب جدید صرفاً به دلیل Delta حذف ساخته نمی‌شود.

نمونهٔ مفهومی PATCH:

```json
{
  "schemas": ["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
  "Operations": [
    { "op": "replace", "path": "active", "value": false }
  ]
}
```

### سناریو E — افزودن مجدد کاربر به گروه Email

- در صورت وجود حساب غیرفعال، حساب با `externalId` پیدا می‌شود.
- اگر Snapshot جاری عضویت در Email را تأیید کند (یا در نبود Snapshot، Delta مثبت معتبر باشد) و Active Claim اجازه دهد، PATCH با `active=true` ارسال می‌شود.
- `userName` و `externalId` حساب موجود تغییر نمی‌کنند.
- اگر Active Claim مقدار false داشته باشد، حساب با وجود عضویت در Email غیرفعال باقی می‌ماند.

### سناریو F — حذف کاربر از WSO2

- رویداد DELETE کاربر در کانکتور به حذف فیزیکی SCIM تبدیل نمی‌شود.
- کانکتور حساب Stalwart را با `externalId` پیدا می‌کند.
- برای حساب موجود، فقط `active=false` ارسال می‌شود.
- اگر حساب مقصد از قبل وجود نداشته باشد، عملیات از نظر دسترسی بی‌اثر است.
- Cache مربوط به شناسهٔ حساب نیز نامعتبر می‌شود.
- هیچ SCIM DELETE برای User صادر نمی‌شود.

### سناریو G — تغییر FIRSTNAME یا LASTNAME یا DISPLAYNAME

- هر یک از سه Claim به‌تنهایی محرک Update است (OR، نه AND).
- برای تغییر FIRSTNAME یا LASTNAME، مقادیر کامل جاری از UserStore خوانده و یک `displayName` کامل ساخته می‌شود.
- برای تغییر DISPLAYNAME، مقدار آن مستقیماً اعمال می‌شود؛ مقدار خالی می‌تواند فیلد را پاک کند.
- PATCH فقط شامل `displayName` است.
- `userName` و `externalId` در این درخواست حضور ندارند و تغییر نمی‌کنند.

### سناریو H — تغییر ایمیل، Alias یا username داخلی WSO2

- این تغییرات عمداً به Stalwart منتقل نمی‌شوند.
- این مقادیر باعث Update پروفایل نمی‌شوند.
- حساب موجود با ایمیل جدید جست‌وجو یا تصاحب نمی‌شود.
- `externalId` ثابت، کلید تطبیق حساب باقی می‌ماند.

### سناریو I — رویداد تکراری، Retry یا Race در ساخت

- پیش از ساخت، کانکتور بر اساس `externalId` جست‌وجو می‌کند.
- اگر حساب با همان `externalId` وجود داشته باشد، ساخت مجدد انجام نمی‌شود و عملیات به Reconciliation تبدیل می‌شود.
- اگر `userName` متعلق به `externalId` دیگری باشد، خطا داده می‌شود.
- در پاسخ 409، تطبیق مجدد بر اساس externalId انجام می‌شود.
- در نتیجهٔ مبهم POST، کانکتور قبل از هر تلاش مجدد، وجود حساب با externalId را بررسی می‌کند.

## ۷. عملیات SCIM مورد استفاده

| هدف | روش و مسیر | توضیح |
|---|---|---|
| ایجاد User | `POST /Users` | فقط با userName ایمیل و externalId پایدار |
| جست‌وجوی User | `POST /Users/.search` | فیلتر بر اساس externalId |
| خواندن User برای تطبیق Conflict | `GET /Users/{id}` | بررسی مالکیت externalId |
| تغییر پروفایل | `PATCH /Users/{id}` | فقط displayName |
| فعال/غیرفعال‌سازی | `PATCH /Users/{id}` | فقط active |
| حذف از WSO2 | `PATCH /Users/{id}` | active=false؛ نه DELETE |
| Bulk | `POST /Bulk` | فقط در حالت BULK و در صورت پشتیبانی مقصد |

جست‌وجو از POST به مسیر `.search` استفاده می‌کند تا شناسه‌ها و ایمیل در Query String و لاگ‌های URL قرار نگیرند.

هیچ عملیات `/Groups` در Stalwart برای Provisioning گروه اجرا نمی‌شود.

## ۸. حالت INDIVIDUAL و BULK

### ۸.۱. INDIVIDUAL

- هر عملیات به درخواست SCIM مستقل تبدیل می‌شود.
- هر درخواست تا دریافت پاسخ مقصد منتظر می‌ماند.
- برای عیب‌یابی ساده‌تر و بار کم مناسب است.
- محدودیت هم‌زمانی با `max-concurrent-requests` کنترل می‌شود.

### ۸.۲. BULK

- درخواست‌های هم‌زمان در یک پنجرهٔ کوتاه در حافظه تجمیع می‌شوند.
- پنجرهٔ تجمیع فعلی حدود ۱۵۰ میلی‌ثانیه است.
- با رسیدن Batch به ۱۰۰ عملیات، ارسال زودتر آغاز می‌شود.
- اندازهٔ Payload با `bulk-max-payload-bytes` محدود است؛ دستهٔ بزرگ به Chunkهای کوچک‌تر تقسیم می‌شود.
- هر فراخواننده نتیجهٔ Operation متناظر خودش را دریافت می‌کند؛ پاسخ موفق یک Operation به معنی موفقیت بقیه نیست.
- کلید Batch شامل Tenant، User Store Domain، endpoint، fingerprint توکن، محدودیت Payload، timeout، سیاست fallback و سقف هم‌زمانی است.
- اگر Tenant ID معتبر در Thread Context موجود نباشد، کانکتور برای جلوگیری از تجمیع بین Tenantها به INDIVIDUAL برمی‌گردد.
- سقف عملیات معلق در حافظه محدود است تا از رشد نامحدود صف جلوگیری شود.

**Retry در Bulk:**

- پاسخ HTTP سطح Batch با 408، 429 یا 5xx فقط در صورت امن‌بودن تمام عملیات Batch، با تعداد تلاش محدود و Backoff انجام می‌شود.
- ممکن است HTTP کلی 200 باشد ولی یک Operation داخل پاسخ 429 یا 5xx برگرداند. در این حالت فقط همان Operation ناموفق، نه کل Batch، از مسیر Individual و با Retry محدود پیگیری می‌شود.
- PATCH فقط زمانی Retry می‌شود که مجموعهٔ عملیات آن idempotent و از نوع replace باشد.
- POST کاربر فقط با userName و externalId پایدار قابل Retry است و پیش از تکرار با externalId تطبیق می‌شود.
- Operationهای موفق Batch دوباره اجرا نمی‌شوند.
- اگر مقصد Bulk را با 404، 405 یا 501 پشتیبانی نکند و `bulk-fallback-to-individual=true` باشد، Batch به درخواست‌های مستقل تبدیل می‌شود.
- خطای مبهم شبکه به معنی شکست قطعی نیست. کانکتور POST را با externalId تطبیق می‌دهد و از Replay کورکورانه جلوگیری می‌کند.

**محدودیت مهم:** Batch در حافظه است و Outbox یا ذخیرهٔ پایدار ندارد. توقف ناگهانی WSO2 ممکن است عملیات هنوز ارسال‌نشده را از بین ببرد. در چنین شرایطی باید وضعیت مقصد و لاگ WSO2 بررسی و در صورت لزوم Provisioning مجدداً همگام‌سازی شود.

## ۹. تنظیمات کانکتور

تنظیمات از UI کانکتور WSO2 دریافت می‌شوند. در صورت وجود فایل TOML، مقادیر تعریف‌شده در آن پس از تنظیمات UI اعمال می‌شوند و اولویت دارند. فایل TOML اختیاری است.

### ۹.۱. فهرست Propertyها

| کلید | نوع | مقدار/پیش‌فرض | توضیح |
|---|---|---|---|
| `scim-base-url` | String | اجباری | آدرس پایه با انتهای `/scim/v2` |
| `scim-bearer-token` | Secret String | اجباری | API Key مقصد |
| `scim-user-store-domain` | String | `PRIMARY` | دامنهٔ User Store برای Claimهای چنددامنه‌ای |
| `enable-claim-mapping` | Boolean | `true` | استفاده از Claim Mapping |
| `username-claim-uri` | String | Claim ایمیل | Claim ایمیل اصلی |
| `external-id-claim-uri` | String | Claim username | شناسهٔ پایدار WSO2 |
| `given-name-claim-uri` | String | Claim givenname | نام |
| `family-name-claim-uri` | String | Claim lastname | نام خانوادگی |
| `display-name-claim-uri` | String | Claim displayname | نام نمایشی |
| `enable-profile-updates` | Boolean | `true` | فعال‌بودن Update پروفایل |
| `profile-update-scim-url` | String | خالی | URL جایگزین فقط با همان Origin |
| `max-concurrent-requests` | Integer | `8` | بازهٔ معتبر 1 تا 64 |
| `active-claim-uri` | String | خالی | Claim بولی تکمیلی |
| `invert-active-claim` | Boolean | `false` | معکوس‌کردن معنی Active Claim |
| `email-membership-group` | String | `Email` | نام گروه مجوز ایمیل در WSO2 |
| `provisioning-mode` | String | `INDIVIDUAL` | `INDIVIDUAL` یا `BULK` |
| `bulk-fallback-to-individual` | Boolean | `true` | fallback در نبود Bulk |
| `bulk-request-timeout-ms` | Integer | `30000` | بازهٔ معتبر 1000 تا 120000 |
| `bulk-max-payload-bytes` | Integer | `900000` | بازهٔ معتبر 1024 تا 1048576 |

### ۹.۲. نمونه TOML

فایل نمونه در مسیر `config/stalwart-scim-connector.toml.example` قرار دارد:

```toml
[stalwart-scim]
scim-base-url = "https://mail.example.net/scim/v2"
scim-bearer-token = "REPLACE_WITH_STALWART_API_KEY"
scim-user-store-domain = "PRIMARY"

enable-claim-mapping = true
username-claim-uri = "http://wso2.org/claims/emailaddress"
external-id-claim-uri = "http://wso2.org/claims/username"
given-name-claim-uri = "http://wso2.org/claims/givenname"
family-name-claim-uri = "http://wso2.org/claims/lastname"
display-name-claim-uri = "http://wso2.org/claims/displayname"

enable-profile-updates = true
profile-update-scim-url = ""
max-concurrent-requests = 8

active-claim-uri = ""
invert-active-claim = false
email-membership-group = "Email"

provisioning-mode = "BULK"
bulk-fallback-to-individual = true
bulk-request-timeout-ms = 30000
bulk-max-payload-bytes = 900000
```

مسیر نهایی:

```text
<WSO2_HOME>/repository/conf/stalwart-scim-connector.toml
```

Parser فعلی TOML عمداً Subset محدودی از مقادیر Scalar را می‌پذیرد: String با کوتیشن دوتایی، Boolean و عدد صحیح. آرایه، Inline Table و Multiline String پشتیبانی نمی‌شود. کلید تکراری یا ناشناخته در بخش `[stalwart-scim]` باعث خطا می‌شود تا خطای تایپی بی‌صدا نادیده گرفته نشود.

برای فایل حاوی Token، مجوز دسترسی را محدود کنید؛ برای نمونه:

```bash
chown wso2carbon:wso2 <WSO2_HOME>/repository/conf/stalwart-scim-connector.toml
chmod 0600 <WSO2_HOME>/repository/conf/stalwart-scim-connector.toml
```

نام کاربر و گروه سیستم‌عامل را متناسب با نصب خود اصلاح کنید.

## ۱۰. نصب و راه‌اندازی

### ۱۰.۱. پیش‌نیازها

- WSO2 IS 7.3.0 با Java 21؛
- Stalwart Enterprise 1.0.2 و SCIM فعال؛
- API Key اختصاصی با مجوزهای لازم برای User؛
- دسترسی شبکه از WSO2 به مقصد؛
- گواهی TLS معتبر که JVM آن را Trust می‌کند؛
- در نصب Docker، Docker CLI و دسترسی به Container.

### ۱۰.۲. Build

در محیط دارای JDK 21 و Maven:

```bash
mvn -B -ntp clean verify
```

مسیر JAR:

```text
components/org.stalwart.identity.provisioning.connector.scim/target/org.stalwart.identity.provisioning.connector.scim-2.0.8-SNAPSHOT.jar
```

### ۱۰.۳. نصب خودکار روی VM یا Docker

از ریشهٔ مخزن:

```bash
chmod +x install.sh uninstall.sh
./install.sh
```

یا مسیر JAR را صریح بدهید:

```bash
./install.sh components/org.stalwart.identity.provisioning.connector.scim/target/org.stalwart.identity.provisioning.connector.scim-2.0.8-SNAPSHOT.jar
```

نصب‌کننده:

1. نوع مقصد (Linux VM یا Docker Container) را می‌پرسد.
2. در حالت Docker، نام Container را دریافت و اعتبارسنجی می‌کند.
3. مسیر `repository/components/dropins` را کشف می‌کند یا در صورت نیاز از کاربر می‌گیرد.
4. مسیر فایل TOML را بر اساس WSO2 Home تشخیص می‌دهد.
5. نسخهٔ JAR را از Manifest بررسی می‌کند.
6. قبل از تغییر، مسیرها و عملیات را نمایش می‌دهد و تأیید می‌گیرد.
7. فقط JARهای همین Bundle را Backup می‌کند؛ JARهای نامرتبط را دست نمی‌زند.
8. فایل TOML موجود را بازنویسی نمی‌کند؛ اگر فایل یا بخش کانکتور موجود نباشد، قالب Comment شده اضافه می‌شود.

نصب‌کننده WSO2 را Restart نمی‌کند. پس از پایان نصب، در زمان مناسب سرویس WSO2 را Restart کنید.

### ۱۰.۴. نصب دستی

1. WSO2 را متوقف کنید.
2. JAR نسخهٔ 2.0.8 را در مسیر زیر قرار دهید:

   `<WSO2_HOME>/repository/components/dropins/`

3. JAR قدیمی همین کانکتور را از `dropins` خارج کنید. سایر Bundleها را حذف نکنید.
4. در صورت استفاده از TOML، فایل را در مسیر `<WSO2_HOME>/repository/conf/stalwart-scim-connector.toml` بسازید.
5. WSO2 را اجرا کنید.
6. لاگ را برای فعال‌شدن OSGi Bundle و ثبت Connector Factory بررسی کنید.
7. در پنل WSO2 یک Outbound Provisioning Connector با نوع `stalwart-scim` تعریف کنید.
8. آدرس SCIM، Token و Claim mappingها را تنظیم کنید.
9. برای آزمون اولیه، حالت INDIVIDUAL را انتخاب کنید؛ پس از تأیید سناریوها می‌توانید BULK را فعال کنید.

### ۱۰.۵. حذف نصب

از ریشهٔ مخزن:

```bash
./uninstall.sh
```

اسکریپت JAR همین کانکتور را از مسیر dropins خارج/غیرفعال می‌کند و فایل TOML و سایر Bundleها را نگه می‌دارد. سپس WSO2 را Restart کنید. حذف JAR به‌معنی حذف حساب‌های قبلی از Stalwart نیست؛ حساب‌های مقصد با این عملیات تغییر نمی‌کنند.

## ۱۱. امنیت و حریم داده

- در Production فقط HTTPS استفاده کنید. HTTP فقط برای localhost و آزمون محلی مجاز است.
- گواهی TLS را اعتبارسنجی کنید؛ TLS verification را غیرفعال نکنید.
- Token را در مخزن، README، لاگ یا خط فرمان عمومی قرار ندهید.
- Token در لاگ کانکتور چاپ نمی‌شود.
- جست‌وجوی User از POST `.search` استفاده می‌کند تا دادهٔ هویتی در URL قرار نگیرد.
- اگر `profile-update-scim-url` تنظیم شود، باید دقیقاً همان Origin (scheme/host/port) آدرس اصلی را داشته باشد؛ Token به میزبان دیگر ارسال نمی‌شود.
- هیچ Password، شماره تلفن، Alias، عکس یا Claim نامرتبطی به Stalwart ارسال نمی‌شود.
- از API Key با حداقل مجوز لازم استفاده کنید.
- دسترسی فایل TOML را فقط به حساب سرویس WSO2 محدود کنید.
- Cache شناسه‌ها به Tenant و داده‌های اتصال محدود است؛ Batch نیز Tenant-aware است. اگر Tenant context قابل شناسایی نباشد، تجمیع Bulk انجام نمی‌شود.

## ۱۲. رفتار خطا و بازیابی

| وضعیت | رفتار |
|---|---|
| ایمیل اصلی ناموجود/نامعتبر هنگام Create | ایجاد رد می‌شود |
| externalId ناموجود/نامعتبر | ایجاد/تطبیق رد می‌شود |
| عضویت Email اثبات نشده | حساب فعال نمی‌شود |
| Active Claim تنظیم‌شده ولی ناموجود/نامعتبر | خطا؛ فعال‌سازی انجام نمی‌شود |
| User با externalId موجود | Reconcile؛ از ایجاد تکراری جلوگیری می‌شود |
| userName متعلق به externalId دیگر | خطای Conflict امنیتی؛ حساب تصاحب نمی‌شود |
| HTTP 409 برای Create | تطبیق مجدد با externalId |
| HTTP 404 برای PATCH کاربر | خطا/عدم تطبیق؛ حساب دیگری انتخاب نمی‌شود |
| Bulk با 404/405/501 و fallback روشن | تبدیل عملیات به درخواست مستقل |
| Bulk Operation با 408/429/5xx | فقط همان عملیات امن با مسیر Individual دوباره تلاش می‌شود |
| خطای مبهم شبکه در POST | بررسی externalId؛ بدون Replay کورکورانه |
| پاسخ Bulk ناقص یا تعداد Operation متفاوت | خطا؛ Batch به‌عنوان موفقیت ثبت نمی‌شود |
| توقف ناگهانی WSO2 | عملیات ارسال‌نشده در حافظه ممکن است از دست بروند؛ Outbox وجود ندارد |

برای خطاهای Provisioning ابتدا لاگ WSO2، سپس وضعیت User با externalId در Stalwart و در نهایت عضویت گروه Email در WSO2 را بررسی کنید. اگر نتیجهٔ شبکه مبهم است، قبل از Retry دستی، وجود حساب مقصد را با externalId بررسی کنید.

## ۱۳. سناریوهای آزمون پذیرش

### ۱۳.۱. تست‌های عملکردی اجباری

| شماره | سناریو | نتیجهٔ مورد انتظار |
|---|---|---|
| 1 | Create با Email membership و Active=true | یک User با active=true |
| 2 | Create بدون Email membership | User غیرفعال |
| 3 | Create با Active Claim=false | User غیرفعال |
| 4 | Active=true بدون عضویت Email | User فعال نشود |
| 5 | Email Claim مفقود در رویداد Group Add | بازیابی از UserStore؛ در صورت عدم امکان خطا |
| 6 | Group Add با Snapshot شامل Email | ایجاد/فعال‌سازی در صورت تأیید Active Claim |
| 7 | Group Add مثبت با Snapshot خالی | Snapshot اولویت دارد؛ حساب جدید ایجاد/فعال نشود |
| 8 | Group Remove | PATCH active=false |
| 9 | افزودن مجدد به Email | PATCH active=true در صورت اجازهٔ Active Claim |
| 10 | Delete کاربر در WSO2 | PATCH active=false؛ بدون SCIM DELETE |
| 11 | تغییر FIRSTNAME به‌تنهایی | PATCH displayName کامل |
| 12 | تغییر LASTNAME به‌تنهایی | PATCH displayName کامل |
| 13 | تغییر DISPLAYNAME به‌تنهایی | PATCH displayName |
| 14 | خالی‌شدن DISPLAYNAME | پاک‌شدن مقدار displayName در مقصد |
| 15 | تغییر ایمیل یا Alias | بدون Update در Stalwart |
| 16 | تغییر username داخلی WSO2 | عدم تغییر userName و externalId مقصد |
| 17 | Create تکراری با externalId یکسان | بدون حساب تکراری |
| 18 | userName متعلق به externalId دیگر | رد امن؛ عدم تصاحب حساب |
| 19 | پاسخ Bulk با Operation موفق و Operation 429 | فقط Operation ناموفق Retry شود |
| 20 | Bulk غیرپشتیبانی‌شده با fallback روشن | ارسال Individual |
| 21 | پاسخ Bulk ناقص | خطا؛ عدم اعلام موفقیت کاذب |
| 22 | دو Tenant با شناسهٔ یکسان | عدم اشتراک Cache یا Batch |
| 23 | Claimهای FIRSTNAME/LASTNAME از UserStore ثانویه | خواندن از دامنهٔ درست |
| 24 | Bundle restart حین کار | خطا/تکمیل کنترل‌شده؛ عدم ادعای تحویل پایدار |

### ۱۳.۲. تست‌های موجود در CI

Workflow مخزن موارد زیر را اجرا می‌کند:

- بررسی Syntax اسکریپت‌های نصب و حذف؛
- اجرای `mvn clean verify`؛
- تست‌های Smoke برای Factory و TOML؛
- تجمیع Bulk و Retry پاسخ HTTP سطح Batch؛
- تست Retry فقط Operation ناموفق در پاسخ Bulk با HTTP 200؛
- بازیابی ایمیل و پروفایل مفقود در رویداد Group Add از UserStore؛
- تقدم Snapshot کامل گروه بر Delta متناقض؛
- تطبیق Create با پاسخ 409 و جلوگیری از ساخت تکراری؛
- تغییر مستقل FIRSTNAME، LASTNAME و DISPLAYNAME و خواندن Snapshot کامل نام؛
- رد Active Claim نامعتبر یا مفقود؛
- جداسازی Cache شناسه‌ها بین Tenantها؛
- تبدیل Delete به PATCH `active=false`؛
- عدم تغییر Claimهای هویتی و پاک‌کردن displayName؛
- بررسی ساخت Bundle و عدم بسته‌بندی APIهای WSO2 و Outbox.

این تست‌ها از HTTP Mock و محیط CI استفاده می‌کنند و جایگزین آزمون روی WSO2/Stalwart واقعی نیستند.

### ۱۳.۳. تست‌های عملیاتی روی محیط واقعی

قبل از Production، روی یک Tenant آزمایشی موارد زیر را انجام دهید:

1. نصب Bundle و بررسی وضعیت OSGi و تزریق RealmService؛
2. ساخت کاربر با گروه Email و بررسی User در Stalwart؛
3. ساخت کاربر بدون گروه Email و اطمینان از active=false؛
4. افزودن و حذف گروه Email و بررسی فعال/غیرفعال‌شدن؛
5. آزمون Active Claim با true، false، مقدار خالی و مقدار غیر Boolean؛
6. آزمون Group Add در حالی که ایمیل در Outbound Attribute نیست و باید از UserStore خوانده شود؛
7. تغییر FIRSTNAME، LASTNAME و DISPLAYNAME به‌صورت جداگانه؛
8. تغییر ایمیل و اطمینان از عدم تغییر userName مقصد؛
9. حذف کاربر از WSO2 و اطمینان از PATCH به‌جای DELETE؛
10. اجرای Bulk با پاسخ ترکیبی و بار هم‌زمان؛
11. قطع شبکه در زمان POST/Bulk و بررسی نتیجهٔ مبهم؛
12. آزمون چند Tenant و چند User Store؛
13. Restart و Upgrade/Uninstall Bundle.

## ۱۴. محدودیت‌ها و ملاحظات شناخته‌شده

- نسخهٔ 2.0.8 در این شاخه با پسوند SNAPSHOT ساخته می‌شود و هنوز Release نهایی نیست.
- اجرای CI اثبات نمی‌کند که Bundle در OSGi واقعی WSO2 فعال می‌شود یا همهٔ رفتارهای UserStore و Claim Mapping در تنظیمات محیط مقصد درست هستند.
- Bulk دارای صف پایدار نیست؛ Crash ممکن است عملیات در صف را از بین ببرد.
- exactly-once تضمین نمی‌شود. برای عملیات با نتیجهٔ مبهم، تطبیق با externalId و بررسی وضعیت مقصد ضروری است.
- Parser فایل TOML، Parser کامل استاندارد TOML نیست و فقط Scalarهای مستندشده را می‌پذیرد.
- این کانکتور Groupهای WSO2 را به Groupهای Stalwart تبدیل نمی‌کند.
- تغییر ایمیل کاربر پس از ایجاد، عمداً به‌صورت خودکار در Stalwart اعمال نمی‌شود.
- حذف کاربر به معنی حذف داده‌های او از Stalwart نیست؛ فقط دسترسی حساب غیرفعال می‌شود.

## ۱۵. ساختار مخزن

```text
.
├── .github/workflows/build.yml
├── components/
│   └── org.stalwart.identity.provisioning.connector.scim/
│       ├── src/main/java/                 # Connector, Factory, UserStore snapshot, OSGi component
│       ├── src/test/java/                 # Executable smoke/regression tests
│       └── pom.xml
├── config/
│   └── stalwart-scim-connector.toml.example
├── install.sh
├── uninstall.sh
├── LICENSE
├── README.md
└── pom.xml
```

## ۱۶. مجوز

Apache License 2.0. اعلان‌های حق‌نشر و مجوزهای فایل‌های مشتق‌شده باید مطابق مجوز منبع حفظ شوند.
