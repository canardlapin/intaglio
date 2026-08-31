args <- commandArgs(trailingOnly = TRUE)
out_dir <- if (length(args) >= 1L) args[[1L]] else "target/feature-visual-qa/reference"
dir.create(out_dir, recursive = TRUE, showWarnings = FALSE)

suppressPackageStartupMessages(library(ggplot2))
suppressPackageStartupMessages(library(patchwork))

blue <- "#467DB4"
orange <- "#DC7D37"
green <- "#379169"
navy <- "#233C5A"

visits <- data.frame(
  day = as.Date(c(
    "2024-01-01", "2024-02-01", "2024-03-01",
    "2024-04-01", "2024-05-01", "2024-06-01"
  )),
  score = c(1.0, 1.7, 1.4, 2.5, 2.1, 3.0)
)
temporal_zoom <- ggplot(visits, aes(day, score)) +
  geom_line(colour = blue, linewidth = 0.75) +
  geom_point(shape = 21, size = 2.4, stroke = 0.6, colour = navy, fill = orange) +
  scale_x_date(date_breaks = "1 month", date_labels = "%Y-%m-%d") +
  coord_cartesian(
    xlim = as.Date(c("2024-01-15", "2024-05-15")),
    expand = FALSE,
    clip = "on"
  ) +
  labs(title = "Temporal zoom", x = "visit", y = "score") +
  theme_minimal(base_size = 12)

style_points <- data.frame(
  x = 1:4,
  y = rep(3, 4),
  shape = factor(c("circle", "square", "triangle", "cross"),
    levels = c("circle", "square", "triangle", "cross")
  ),
  size = c(5, 7, 9, 11),
  colour = c(blue, green, orange, navy),
  fill = c(orange, "#FFFFFF", blue, "#FFFFFF")
)
style_lines <- data.frame(
  x = rep(1:4, 2),
  y = c(1.0, 1.8, 1.3, 2.0, 2.0, 1.2, 2.2, 1.5),
  group = factor(rep(c("solid", "dashed"), each = 4), levels = c("solid", "dashed")),
  colour = rep(c(blue, orange), each = 4),
  linewidth = rep(c(1.5, 2.5), each = 4)
)
style_text <- data.frame(
  x = c(1, 2.5, 4),
  y = c(4, 4, 4),
  label = c("left", "center", "right"),
  angle = c(-30, 0, 30),
  hjust = c(0, 0.5, 1),
  vjust = c(0, 0.5, 1)
)
style_aesthetics <- ggplot() +
  geom_line(
    data = style_lines,
    aes(x, y, group = group, colour = colour, linetype = group, linewidth = linewidth),
    lineend = "butt"
  ) +
  geom_point(
    data = style_points,
    aes(x, y, shape = shape, size = size, colour = colour, fill = fill),
    stroke = 0.8
  ) +
  geom_text(
    data = style_text,
    aes(x, y, label = label, angle = angle, hjust = hjust, vjust = vjust),
    colour = navy
  ) +
  scale_shape_manual(values = c(circle = 21, square = 22, triangle = 24, cross = 4)) +
  scale_size_identity() +
  scale_colour_identity() +
  scale_fill_identity() +
  scale_linetype_manual(values = c(solid = "solid", dashed = "dashed")) +
  scale_linewidth_identity() +
  labs(title = "Typed style aesthetics", x = "x", y = "y") +
  theme_minimal(base_size = 12) +
  theme(legend.position = "none")

distribution <- data.frame(
  value = c(2, 1, 2, 4, 3, 3, 1),
  group = factor(c("A", "A", "A", "A", "B", "B", "B"), levels = c("A", "B"))
)
ecdf_baseline <- data.frame(
  value = c(1, 1),
  first_proportion = c(0.25, 1 / 3),
  group = factor(c("A", "B"), levels = c("A", "B"))
)
ecdf <- ggplot(distribution, aes(value, colour = group, group = group)) +
  geom_segment(
    data = ecdf_baseline,
    aes(x = value, xend = value, y = 0, yend = first_proportion, colour = group),
    inherit.aes = FALSE,
    linewidth = 0.75
  ) +
  stat_ecdf(geom = "step", pad = FALSE, linewidth = 0.75) +
  scale_colour_manual(values = c(A = blue, B = orange)) +
  labs(title = "Grouped ECDF", x = "value", y = "cumulative proportion") +
  theme_minimal(base_size = 12) +
  theme(legend.position = "none")

summary_data <- data.frame(
  position = c(rep(1, 5), rep(2, 4)),
  value = c(1, 2, 3, 4, 100, 0, 10, 20, 30)
)
quantile_summary <- ggplot(summary_data, aes(position, value)) +
  stat_summary(
    fun = median,
    fun.min = function(x) unname(quantile(x, 0.25, type = 7)),
    fun.max = function(x) unname(quantile(x, 0.75, type = 7)),
    geom = "pointrange",
    colour = navy,
    linewidth = 0.75,
    size = 2.4
  ) +
  labs(title = "Type-7 quartile summary", x = "group", y = "value") +
  theme_minimal(base_size = 12)

compact <- data.frame(x = 1:3, y = c(1, 2, 1.5))
wide <- data.frame(x = 1:3, y = c(10000, 30000, 20000))
compact_plot <- ggplot(compact, aes(x, y)) +
  geom_line(colour = blue, linewidth = 0.6) +
  geom_point(shape = 21, colour = navy, fill = blue, size = 2.4) +
  labs(title = "Compact scale", x = "x", y = "response") +
  theme_minimal(base_size = 12)
wide_plot <- ggplot(wide, aes(x, y)) +
  geom_line(colour = orange, linewidth = 0.6) +
  geom_point(shape = 21, colour = navy, fill = orange, size = 2.4) +
  labs(title = "Wide labels", x = "x", y = "response") +
  theme_minimal(base_size = 12)
composition <- compact_plot + wide_plot + plot_layout(ncol = 2)

plots <- list(
  `temporal-zoom` = temporal_zoom,
  `style-aesthetics` = style_aesthetics,
  ecdf = ecdf,
  `quantile-summary` = quantile_summary,
  composition = composition
)
for (name in names(plots)) {
  ggsave(
    filename = file.path(out_dir, paste0(name, ".png")),
    plot = plots[[name]],
    width = 6.4,
    height = 4.8,
    dpi = 100,
    bg = "white"
  )
}

write_tsv <- function(value, name) {
  write.table(
    value,
    file.path(out_dir, name),
    sep = "\t",
    row.names = FALSE,
    quote = FALSE
  )
}
write_tsv(layer_data(temporal_zoom, 1)[c("x", "y")], "temporal-line-layer.tsv")
write_tsv(layer_data(temporal_zoom, 2)[c("x", "y")], "temporal-point-layer.tsv")
write_tsv(
  layer_data(style_aesthetics, 1)[c("x", "y", "group", "colour", "linetype", "linewidth")],
  "style-line-layer.tsv"
)
write_tsv(
  layer_data(style_aesthetics, 2)[c("x", "y", "shape", "size", "colour", "fill")],
  "style-point-layer.tsv"
)
write_tsv(
  layer_data(style_aesthetics, 3)[c("x", "y", "label", "angle", "hjust", "vjust")],
  "style-text-layer.tsv"
)
write_tsv(
  layer_data(ecdf, 1)[c("x", "xend", "y", "yend", "group", "colour")],
  "ecdf-baseline-layer.tsv"
)
write_tsv(layer_data(ecdf, 2)[c("x", "y", "group", "colour")], "ecdf-layer.tsv")
write_tsv(
  layer_data(quantile_summary)[c("x", "y", "ymin", "ymax")],
  "quantile-summary-layer.tsv"
)
write_tsv(layer_data(compact_plot)[c("x", "y")], "composition-compact-layer.tsv")
write_tsv(layer_data(wide_plot)[c("x", "y")], "composition-wide-layer.tsv")

manifest <- data.frame(
  case = names(plots),
  peer = c("ggplot2", "ggplot2", "ggplot2", "ggplot2", "ggplot2 + patchwork"),
  ggplot2_version = as.character(packageVersion("ggplot2")),
  patchwork_version = as.character(packageVersion("patchwork"))
)
write_tsv(manifest, "reference-manifest.tsv")

cat("wrote recent-feature peer references to", out_dir, "\n")
