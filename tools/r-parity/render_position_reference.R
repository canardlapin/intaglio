args <- commandArgs(trailingOnly = TRUE)
out_dir <- if (length(args) >= 1L) args[[1L]] else "target/graphics-position-qa/ggplot2"
dir.create(out_dir, recursive = TRUE, showWarnings = FALSE)

suppressPackageStartupMessages(library(ggplot2))

palette <- c(red = "#467DB4", blue = "#DC8741")
series <- data.frame(
  x = rep(c(0, 1, 2), 2),
  y = c(1, 1.8, 2.5, 2, 2.7, 3.2),
  condition = factor(rep(c("A", "B"), each = 3), levels = c("A", "B"))
)

scatter <- ggplot(series, aes(x, y, colour = condition)) +
  geom_point(size = 2) +
  scale_colour_manual(values = c(A = palette[["red"]], B = palette[["blue"]])) +
  labs(title = "scatter", x = "x", y = "y") +
  theme_minimal(base_size = 12) +
  theme(legend.position = "none")

grouped_line <- ggplot(series, aes(x, y, group = condition, colour = condition)) +
  geom_line(linewidth = 0.4) +
  scale_colour_manual(values = c(A = palette[["red"]], B = palette[["blue"]])) +
  labs(title = "grouped-line", x = "x", y = "y") +
  theme_minimal(base_size = 12) +
  theme(legend.position = "none")

distribution <- data.frame(
  value = c(0, 0.4, 0.9, 1, 1.3, 1.8, 2, 2.2, 2.7, 3.1, 3.6, 4)
)
histogram <- ggplot(distribution, aes(value)) +
  geom_histogram(
    breaks = c(0, 1, 2, 3, 4),
    closed = "right",
    colour = "#467DB4",
    fill = "#5A96CD"
  ) +
  labs(title = "histogram", x = "value", y = "count") +
  theme_minimal(base_size = 12)

density_plot <- ggplot(distribution, aes(value)) +
  stat_density(
    geom = "line",
    bw = 0.45,
    n = 64,
    trim = TRUE,
    colour = "#467DB4",
    linewidth = 0.4
  ) +
  labs(title = "density", x = "value", y = "density") +
  theme_minimal(base_size = 12)

summary_data <- data.frame(
  x = rep(c(0, 1, 2), each = 3),
  y = c(1, 2, 3, 2, 4, 6, 4, 5, 6)
)
summarized <- ggplot(summary_data, aes(x, y)) +
  stat_summary(fun.data = mean_se, geom = "pointrange", colour = "#467DB4", linewidth = 0.4) +
  labs(title = "mean-and-se", x = "x", y = "mean") +
  theme_minimal(base_size = 12)

ribbon_data <- data.frame(
  x = c(0, 1, 2, 3, 4),
  lower = c(0.8, 1.2, 1, 1.6, 1.3),
  upper = c(1.5, 2, 1.8, 2.4, 2)
)
ribbon <- ggplot(ribbon_data, aes(x, ymin = lower, ymax = upper)) +
  geom_ribbon(colour = "#467DB4", fill = "#467DB4", alpha = 0.45, linewidth = 0.3) +
  labs(title = "ribbon", x = "x", y = "interval") +
  theme_minimal(base_size = 12)

tile_data <- data.frame(
  x = rep(c(0, 1, 2), 2),
  y = rep(c(0, 1), each = 3),
  level = factor(c(0, 1, 2, 2, 1, 0), levels = c(0, 1, 2))
)
tiles <- ggplot(tile_data, aes(x, y, fill = level)) +
  geom_tile(width = 1, height = 1, colour = "white", linewidth = 0.4) +
  scale_fill_manual(values = c("#E1EBF5", "#7DAAD2", "#2D5F91")) +
  labs(title = "tiles", x = "x", y = "y") +
  theme_minimal(base_size = 12) +
  theme(legend.position = "none")

heatmap_data <- data.frame(
  x = rep(c(0.5, 1.5, 2.5), 2),
  y = rep(c(0.5, 1.5), each = 3),
  value = c(0, 1, 2, 2, 1, 0)
)
heatmap <- ggplot(heatmap_data, aes(x, y, fill = value)) +
  geom_tile(width = 1, height = 1) +
  scale_fill_gradient(low = "#EFF3FF", high = "#08519C", name = "value") +
  labs(title = "heatmap", x = "x", y = "y") +
  theme_minimal(base_size = 12)

bin2d_data <- data.frame(
  x = c(0.2, 0.4, 0.7, 1.2, 1.8, 2.2, 2.4, 2.6, 3.2, 3.7),
  y = c(0.2, 0.3, 0.8, 2.2, 2.7, 1.2, 1.4, 1.6, 3.2, 3.6)
)
bin2d <- ggplot(bin2d_data, aes(x, y)) +
  geom_bin_2d(
    binwidth = c(1, 1), boundary = 0, closed = "right", drop = FALSE
  ) +
  scale_x_continuous(limits = c(0, 4), expand = expansion(mult = 0)) +
  scale_y_continuous(limits = c(0, 4), expand = expansion(mult = 0)) +
  scale_fill_gradient(low = "#EFF3FF", high = "#08519C", name = "count") +
  labs(title = "bin-2d", x = "x", y = "y") +
  theme_minimal(base_size = 12)

kde2d_data <- data.frame(
  x = c(-1.4, -1.1, -0.8, -0.5, 0.5, 0.9, 1.2, 1.5),
  y = c(-1.0, -0.7, -1.2, -0.6, 0.8, 1.2, 0.7, 1.4)
)
kde2d <- ggplot(kde2d_data, aes(x, y)) +
  stat_density_2d(
    aes(fill = after_stat(density)),
    geom = "tile",
    contour = FALSE,
    h = c(2.4, 2.8),
    n = 40
  ) +
  scale_x_continuous(limits = c(-3, 3), expand = expansion(mult = 0), oob = scales::oob_keep) +
  scale_y_continuous(limits = c(-3, 3), expand = expansion(mult = 0), oob = scales::oob_keep) +
  scale_fill_gradient(low = "#EFF3FF", high = "#08519C", name = "density") +
  labs(title = "density-2d", x = "x", y = "y") +
  theme_minimal(base_size = 12)

contour <- ggplot(kde2d_data, aes(x, y)) +
  geom_density_2d(
    h = c(2.4, 2.8),
    n = 80,
    breaks = c(0.03, 0.06, 0.09, 0.12),
    colour = "#467DB4",
    linewidth = 0.4
  ) +
  scale_x_continuous(limits = c(-3, 3), expand = expansion(mult = 0), oob = scales::oob_keep) +
  scale_y_continuous(limits = c(-3, 3), expand = expansion(mult = 0), oob = scales::oob_keep) +
  labs(title = "contour", x = "x", y = "y") +
  theme_minimal(base_size = 12)

filled_contour <- ggplot(kde2d_data, aes(x, y)) +
  geom_density_2d_filled(
    aes(fill = after_stat(level_mid)),
    h = c(2.4, 2.8),
    n = 40,
    breaks = c(0.02, 0.05, 0.08, 0.11, 0.15),
    colour = NA
  ) +
  scale_x_continuous(limits = c(-3, 3), expand = expansion(mult = 0), oob = scales::oob_keep) +
  scale_y_continuous(limits = c(-3, 3), expand = expansion(mult = 0), oob = scales::oob_keep) +
  scale_fill_gradient(low = "#EFF3FF", high = "#08519C", name = "density") +
  labs(title = "filled-contour", x = "x", y = "y") +
  theme_minimal(base_size = 12)

count_data <- data.frame(category = c("control", "task", "task", "other", "task", "control"))
counted <- ggplot(count_data, aes(category)) +
  geom_bar(width = 0.9, colour = "#233C5A", fill = "#5A96CD") +
  labs(title = "count", x = "category", y = "count") +
  theme_minimal(base_size = 12)

facet_data <- data.frame(
  x = c(0, 1, 10, 20),
  y = c(0, 1, 2, 3),
  condition = factor(c("control", "control", "task", "task"), levels = c("control", "task"))
)
faceted <- ggplot(facet_data, aes(x, y)) +
  geom_point(size = 2, colour = palette[["red"]]) +
  facet_wrap(~condition, ncol = 2) +
  labs(title = "facets", x = "x", y = "y") +
  theme_minimal(base_size = 12)

bars <- data.frame(
  category = factor(c("A", "A", "B", "B"), levels = c("A", "B")),
  value = c(3, 2, 1, 4),
  group = factor(c("red", "blue", "red", "blue"), levels = c("red", "blue"))
)

base_bars <- ggplot(bars, aes(category, value, group = group, fill = group)) +
  scale_fill_manual(values = palette) +
  labs(x = "category", y = "value") +
  theme_minimal(base_size = 12) +
  theme(legend.position = "none")

dodge <- base_bars +
  geom_col(width = 0.9, colour = "#232D37", position = position_dodge(width = 0.9)) +
  ggtitle("position-dodge")
stack <- base_bars +
  geom_col(width = 0.9, colour = "#232D37", position = position_stack()) +
  ggtitle("position-stack")

jitter_points <- data.frame(
  category = factor(c("A", "A", "A", "B", "B", "B"), levels = c("A", "B")),
  value = c(1, 1, 1.6, 2, 2, 2.6),
  group = factor(c("red", "blue", "red", "blue", "red", "blue"), levels = c("red", "blue"))
)
jittered <- ggplot(jitter_points, aes(category, value, colour = group)) +
  geom_point(position = position_jitter(width = 0.22, height = 0.12, seed = 2026), size = 2) +
  scale_colour_manual(values = palette) +
  labs(title = "position-jitter", x = "category", y = "value") +
  theme_minimal(base_size = 12) +
  theme(legend.position = "none")

plots <- list(
  scatter = scatter,
  line = grouped_line,
  histogram = histogram,
  density = density_plot,
  summary = summarized,
  ribbon = ribbon,
  tiles = tiles,
  heatmap = heatmap,
  bin2d = bin2d,
  kde2d = kde2d,
  contour = contour,
  `filled-contour` = filled_contour,
  count = counted,
  facets = faceted,
  dodge = dodge,
  stack = stack,
  jitter = jittered
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

dodge_data <- layer_data(dodge)
stack_data <- layer_data(stack)
jitter_data <- layer_data(jittered)
write.table(
  layer_data(scatter)[c("x", "y", "colour", "group")],
  file.path(out_dir, "scatter-layer.tsv"),
  sep = "\t", row.names = FALSE, quote = FALSE
)
write.table(
  layer_data(grouped_line)[c("x", "y", "colour", "group")],
  file.path(out_dir, "line-layer.tsv"),
  sep = "\t", row.names = FALSE, quote = FALSE
)
write.table(
  layer_data(histogram)[c("x", "y", "count", "xmin", "xmax", "ymin", "ymax")],
  file.path(out_dir, "histogram-layer.tsv"),
  sep = "\t", row.names = FALSE, quote = FALSE
)
write.table(
  layer_data(density_plot)[c("x", "y", "density", "count")],
  file.path(out_dir, "density-layer.tsv"),
  sep = "\t", row.names = FALSE, quote = FALSE
)
write.table(
  layer_data(summarized)[c("x", "y", "ymin", "ymax")],
  file.path(out_dir, "summary-layer.tsv"),
  sep = "\t", row.names = FALSE, quote = FALSE
)
write.table(
  layer_data(ribbon)[c("x", "ymin", "ymax")],
  file.path(out_dir, "ribbon-layer.tsv"),
  sep = "\t", row.names = FALSE, quote = FALSE
)
write.table(
  layer_data(tiles)[c("x", "y", "xmin", "xmax", "ymin", "ymax", "fill")],
  file.path(out_dir, "tiles-layer.tsv"),
  sep = "\t", row.names = FALSE, quote = FALSE
)
write.table(
  layer_data(heatmap)[c("x", "y", "xmin", "xmax", "ymin", "ymax", "fill")],
  file.path(out_dir, "heatmap-layer.tsv"),
  sep = "\t", row.names = FALSE, quote = FALSE
)
write.table(
  layer_data(bin2d)[c("x", "y", "xmin", "xmax", "ymin", "ymax", "count", "density", "fill")],
  file.path(out_dir, "bin2d-layer.tsv"),
  sep = "\t", row.names = FALSE, quote = FALSE
)
write.table(
  layer_data(kde2d)[c("x", "y", "density", "count", "fill")],
  file.path(out_dir, "kde2d-layer.tsv"),
  sep = "\t", row.names = FALSE, quote = FALSE
)
write.table(
  layer_data(contour)[c("x", "y", "level", "piece", "group")],
  file.path(out_dir, "contour-layer.tsv"),
  sep = "\t", row.names = FALSE, quote = FALSE
)
write.table(
  layer_data(filled_contour)[c("x", "y", "level_low", "level_high", "level_mid", "piece", "group", "fill")],
  file.path(out_dir, "filled-contour-layer.tsv"),
  sep = "\t", row.names = FALSE, quote = FALSE
)
write.table(
  layer_data(counted)[c("x", "y", "count", "xmin", "xmax", "ymin", "ymax")],
  file.path(out_dir, "count-layer.tsv"),
  sep = "\t", row.names = FALSE, quote = FALSE
)
write.table(
  layer_data(faceted)[c("x", "y", "PANEL", "group")],
  file.path(out_dir, "facets-layer.tsv"),
  sep = "\t", row.names = FALSE, quote = FALSE
)
write.table(
  dodge_data[c("x", "xmin", "xmax", "y", "group")],
  file.path(out_dir, "dodge-layer.tsv"),
  sep = "\t", row.names = FALSE, quote = FALSE
)
write.table(
  stack_data[c("x", "xmin", "xmax", "y", "ymin", "ymax", "group")],
  file.path(out_dir, "stack-layer.tsv"),
  sep = "\t", row.names = FALSE, quote = FALSE
)
write.table(
  jitter_data[c("x", "y", "group")],
  file.path(out_dir, "jitter-layer.tsv"),
  sep = "\t", row.names = FALSE, quote = FALSE
)

cat("wrote ggplot2 position references to", out_dir, "\n")
