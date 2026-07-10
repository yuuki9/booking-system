param(
    [string]$OutputPath = (Join-Path $PSScriptRoot "..\docs\distributed-architecture.png")
)

Add-Type -AssemblyName System.Drawing

$bitmap = New-Object System.Drawing.Bitmap 1600, 900
$graphics = [System.Drawing.Graphics]::FromImage($bitmap)
$graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$graphics.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
$graphics.Clear([System.Drawing.Color]::White)

function Color([string]$hex) {
    return [System.Drawing.ColorTranslator]::FromHtml($hex)
}

function RoundedPath([float]$x, [float]$y, [float]$w, [float]$h, [float]$r = 14) {
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $d = $r * 2
    $path.AddArc($x, $y, $d, $d, 180, 90)
    $path.AddArc($x + $w - $d, $y, $d, $d, 270, 90)
    $path.AddArc($x + $w - $d, $y + $h - $d, $d, $d, 0, 90)
    $path.AddArc($x, $y + $h - $d, $d, $d, 90, 90)
    $path.CloseFigure()
    return $path
}

function DrawCenteredText($text, $rect, $font, $color) {
    $format = New-Object System.Drawing.StringFormat
    $format.Alignment = [System.Drawing.StringAlignment]::Center
    $format.LineAlignment = [System.Drawing.StringAlignment]::Center
    $brush = New-Object System.Drawing.SolidBrush (Color $color)
    $graphics.DrawString($text, $font, $brush, $rect, $format)
    $brush.Dispose()
    $format.Dispose()
}

function DrawRoundBox($x, $y, $w, $h, $fill, $stroke, $text, $textColor, $fontSize = 13, $bold = $true) {
    $path = RoundedPath $x $y $w $h
    $brush = New-Object System.Drawing.SolidBrush (Color $fill)
    $pen = New-Object System.Drawing.Pen (Color $stroke), 2
    $graphics.FillPath($brush, $path)
    $graphics.DrawPath($pen, $path)
    $style = if ($bold) { [System.Drawing.FontStyle]::Bold } else { [System.Drawing.FontStyle]::Regular }
    $font = New-Object System.Drawing.Font "Arial", $fontSize, $style
    DrawCenteredText $text (New-Object System.Drawing.RectangleF $x, $y, $w, $h) $font $textColor
    $font.Dispose()
    $pen.Dispose()
    $brush.Dispose()
    $path.Dispose()
}

function DrawGroup($x, $y, $w, $h, $fill, $headerFill, $stroke, $title) {
    $path = RoundedPath $x $y $w $h
    $brush = New-Object System.Drawing.SolidBrush (Color $fill)
    $pen = New-Object System.Drawing.Pen (Color $stroke), 2
    $graphics.FillPath($brush, $path)
    $graphics.DrawPath($pen, $path)
    $headerBrush = New-Object System.Drawing.SolidBrush (Color $headerFill)
    $graphics.FillRectangle($headerBrush, $x + 2, $y + 2, $w - 4, 50)
    $font = New-Object System.Drawing.Font "Arial", 11, ([System.Drawing.FontStyle]::Bold)
    DrawCenteredText $title (New-Object System.Drawing.RectangleF $x, $y, $w, 52) $font $stroke
    $font.Dispose()
    $headerBrush.Dispose()
    $pen.Dispose()
    $brush.Dispose()
    $path.Dispose()
}

function DrawCylinder($x, $y, $w, $h, $fill, $stroke, $text, $textColor) {
    $brush = New-Object System.Drawing.SolidBrush (Color $fill)
    $pen = New-Object System.Drawing.Pen (Color $stroke), 2
    $graphics.FillRectangle($brush, $x, $y + 14, $w, $h - 28)
    $graphics.FillEllipse($brush, $x, $y, $w, 28)
    $graphics.FillEllipse($brush, $x, $y + $h - 28, $w, 28)
    $graphics.DrawEllipse($pen, $x, $y, $w, 28)
    $graphics.DrawLine($pen, $x, $y + 14, $x, $y + $h - 14)
    $graphics.DrawLine($pen, $x + $w, $y + 14, $x + $w, $y + $h - 14)
    $graphics.DrawArc($pen, $x, $y + $h - 28, $w, 28, 0, 180)
    $font = New-Object System.Drawing.Font "Arial", 12, ([System.Drawing.FontStyle]::Bold)
    DrawCenteredText $text (New-Object System.Drawing.RectangleF $x, ($y + 16), $w, ($h - 25)) $font $textColor
    $font.Dispose()
    $pen.Dispose()
    $brush.Dispose()
}

function DrawArrow($x1, $y1, $x2, $y2, $color, $dashed = $false, $label = "") {
    $pen = New-Object System.Drawing.Pen (Color $color), 2
    $pen.EndCap = [System.Drawing.Drawing2D.LineCap]::ArrowAnchor
    if ($dashed) { $pen.DashStyle = [System.Drawing.Drawing2D.DashStyle]::Dash }
    $graphics.DrawLine($pen, $x1, $y1, $x2, $y2)
    if ($label) {
        $font = New-Object System.Drawing.Font "Arial", 10, ([System.Drawing.FontStyle]::Regular)
        $labelWidth = [Math]::Max(90, $label.Length * 6.5)
        $labelX = (($x1 + $x2) / 2) - ($labelWidth / 2)
        $labelY = (($y1 + $y2) / 2) - 18
        $background = New-Object System.Drawing.SolidBrush (Color "#FFFFFF")
        $graphics.FillRectangle($background, $labelX, $labelY, $labelWidth, 20)
        DrawCenteredText $label (New-Object System.Drawing.RectangleF $labelX, $labelY, $labelWidth, 20) $font "#475569"
        $background.Dispose()
        $font.Dispose()
    }
    $pen.Dispose()
}

$titleFont = New-Object System.Drawing.Font "Arial", 23, ([System.Drawing.FontStyle]::Bold)
$subFont = New-Object System.Drawing.Font "Arial", 11, ([System.Drawing.FontStyle]::Regular)
DrawCenteredText "Booking System — Distributed Service Architecture" (New-Object System.Drawing.RectangleF 400, 22, 800, 42) $titleFont "#1F2937"
DrawCenteredText "6 application instances · Saga consumers run inside reservation / payment" (New-Object System.Drawing.RectangleF 400, 63, 800, 24) $subFont "#64748B"
DrawCenteredText "Kafka flow: reservation.pending → payment.result → confirmed / refund" (New-Object System.Drawing.RectangleF 480, 88, 640, 20) $subFont "#9A3412"

# Connections first
DrawArrow 160 420 200 420 "#356AE6" $false "HTTP"
DrawArrow 340 420 390 290 "#356AE6" $false ""
DrawArrow 340 420 390 420 "#356AE6" $false ""
DrawArrow 340 420 390 550 "#356AE6" $false ""
DrawArrow 670 420 740 425 "#EA580C" $true ""
DrawArrow 920 400 990 360 "#EA580C" $true ""
DrawArrow 990 480 920 450 "#EA580C" $true ""
DrawArrow 740 450 670 450 "#EA580C" $true ""
DrawArrow 500 660 490 720 "#C0392B" $false ""
DrawArrow 580 660 700 720 "#2563EB" $false ""
DrawArrow 1130 650 1130 720 "#2563EB" $false ""

DrawRoundBox 40 390 120 60 "#FFFFFF" "#5B8DEF" "Client / k6" "#1E4FA3" 13
DrawRoundBox 200 380 140 80 "#356AE6" "#2454C6" "Nginx`nLoad Balancer" "#FFFFFF" 13

DrawGroup 390 160 280 500 "#EFF6FF" "#DBEAFE" "#5B8DEF" "RESERVATION · 3 SERVER INSTANCES`n(API + PaymentResultConsumer + Outbox)"
DrawRoundBox 430 245 200 75 "#FFFFFF" "#5B8DEF" "Reservation`nInstance 1" "#1E4FA3"
DrawRoundBox 430 370 200 75 "#FFFFFF" "#5B8DEF" "Reservation`nInstance 2" "#1E4FA3"
DrawRoundBox 430 495 200 75 "#FFFFFF" "#5B8DEF" "Reservation`nInstance 3" "#1E4FA3"

DrawCylinder 740 370 180 110 "#FFF7ED" "#EA580C" "Kafka`nEvent Backbone`npending · result · refund" "#9A3412"

DrawGroup 990 200 280 450 "#F0FDF4" "#DCFCE7" "#22A06B" "PAYMENT · 3 SERVER INSTANCES`n(PendingConsumer + Mock PG + Outbox)"
DrawRoundBox 1030 285 200 75 "#FFFFFF" "#22A06B" "Payment`nInstance 1" "#166534"
DrawRoundBox 1030 400 200 75 "#FFFFFF" "#22A06B" "Payment`nInstance 2" "#166534"
DrawRoundBox 1030 515 200 75 "#FFFFFF" "#22A06B" "Payment`nInstance 3" "#166534"

DrawCylinder 390 720 200 100 "#FEE2E2" "#C0392B" "Redis`nDistributed Lock / Stock" "#991B1B"
DrawCylinder 640 720 180 100 "#DBEAFE" "#2563EB" "Reservation DB`nPostgreSQL" "#1E3A8A"
DrawCylinder 1040 720 180 100 "#DBEAFE" "#2563EB" "Payment DB`nPostgreSQL" "#1E3A8A"

DrawRoundBox 1180 760 360 55 "#F8FAFC" "#CBD5E1" "Solid: request / data access   ·   Dashed: Kafka event`nCompose demo reservation-consumer (log only) omitted" "#475569" 10 $false

$titleFont.Dispose()
$subFont.Dispose()
$graphics.Dispose()

$outputDirectory = Split-Path -Parent $OutputPath
if (-not (Test-Path $outputDirectory)) {
    New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
}
$bitmap.Save($OutputPath, [System.Drawing.Imaging.ImageFormat]::Png)
$bitmap.Dispose()

Write-Output "Rendered $OutputPath"
