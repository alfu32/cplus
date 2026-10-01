#include <stdio.h>
#include <string.h>
comptime import "stdlib:/ui/printf_renderer.cp";

@test "vnode layout style defaults are explicit zero boxes" {
    UI_ELEMENT(node, "box");
    @assert(node != NULL);
    @assert(node->style.layout.position == UI_POSITION_RELATIVE_PARENT);
    @assert(node->style.layout.top == 0.0);
    @assert(node->style.layout.left == 0.0);
    @assert(node->style.layout.width == 0.0);
    @assert(node->style.layout.height == 0.0);
    ui_vnode_destroy(node);
}

@test "relative-parent resolves from parent top-left" {
    UI_ELEMENT(root, "root");
    UI_ELEMENT(child, "child");
    @assert(ui_vnode_layout(root, UI_POSITION_RELATIVE_PARENT, 20, 10, 300, 200) == 0);
    @assert(ui_vnode_layout(child, UI_POSITION_RELATIVE_PARENT, 7, 5, 30, 40) == 0);
    @assert(UI_CHILD(root, child) == 0);
    ui_layout_result_t layout; memset(&layout,0,sizeof(layout)); @assert(ui_layout_result_init(&layout) == 0);
    @assert(ui_layout_run(root, 800, 600, &layout) == 0);
    @assert(layout.drawables.length == 2);
    @assert(layout.drawables.items[0].as.box.rect.x == 10.0);
    @assert(layout.drawables.items[0].as.box.rect.y == 20.0);
    @assert(layout.drawables.items[1].as.box.rect.x == 15.0);
    @assert(layout.drawables.items[1].as.box.rect.y == 27.0);
    @assert(layout.drawables.items[1].as.box.rect.width == 30.0);
    @assert(layout.drawables.items[1].as.box.rect.height == 40.0);
    ui_layout_result_destroy(&layout); ui_vnode_destroy(root);
}

@test "relative-sibling anchors to previous sibling top-left without flow" {
    UI_ELEMENT(root, "root");
    UI_ELEMENT(first, "first"); UI_ELEMENT(second, "second");
    @assert(ui_vnode_layout(root, UI_POSITION_RELATIVE_PARENT, 100, 50, 400, 300) == 0);
    @assert(ui_vnode_layout(first, UI_POSITION_RELATIVE_PARENT, 20, 10, 100, 80) == 0);
    @assert(ui_vnode_layout(second, UI_POSITION_RELATIVE_SIBLING, 4, 3, 20, 10) == 0);
    @assert(UI_CHILD(root, first) == 0); @assert(UI_CHILD(root, second) == 0);
    ui_layout_result_t layout; memset(&layout,0,sizeof(layout)); @assert(ui_layout_result_init(&layout) == 0);
    @assert(ui_layout_run(root, 800, 600, &layout) == 0);
    @assert(layout.drawables.length == 3);
    @assert(layout.drawables.items[1].as.box.rect.x == 60.0);
    @assert(layout.drawables.items[1].as.box.rect.y == 120.0);
    @assert(layout.drawables.items[2].as.box.rect.x == 63.0);
    @assert(layout.drawables.items[2].as.box.rect.y == 124.0);
    @assert(layout.drawables.items[2].as.box.rect.x != 160.0);
    ui_layout_result_destroy(&layout); ui_vnode_destroy(root);
}

@test "fixed anchors to viewport rather than parent" {
    UI_ELEMENT(root, "root"); UI_ELEMENT(fixed, "fixed");
    @assert(ui_vnode_layout(root, UI_POSITION_RELATIVE_PARENT, 100, 200, 400, 300) == 0);
    @assert(ui_vnode_layout(fixed, UI_POSITION_FIXED, 11, 9, 20, 30) == 0);
    @assert(UI_CHILD(root, fixed) == 0);
    ui_layout_result_t layout; memset(&layout,0,sizeof(layout)); @assert(ui_layout_result_init(&layout) == 0);
    @assert(ui_layout_run(root, 800, 600, &layout) == 0);
    @assert(layout.drawables.items[1].as.box.rect.x == 9.0);
    @assert(layout.drawables.items[1].as.box.rect.y == 11.0);
    ui_layout_result_destroy(&layout); ui_vnode_destroy(root);
}

@test "layout emits styled boxes text child indices and images" {
    int pixels = 1234;
    UI_ELEMENT(root, "panel"); UI_TEXT(text, "hello"); UI_IMAGE(image, &pixels);
    @assert(ui_vnode_layout(root, UI_POSITION_RELATIVE_PARENT, 2, 1, 100, 50) == 0);
    @assert(ui_vnode_background(root, "#101010") == 0);
    @assert(ui_vnode_color(root, "white") == 0);
    @assert(ui_vnode_border(root, "1 solid gray") == 0);
    @assert(ui_vnode_font_family(root, "mono") == 0);
    @assert(ui_vnode_font_size(root, 14) == 0);
    @assert(ui_vnode_font_weight(root, 700) == 0);
    @assert(ui_vnode_font_decoration(root, UI_FONT_DECORATION_UNDERLINE) == 0);
    @assert(ui_vnode_layout(text, UI_POSITION_RELATIVE_PARENT, 4, 3, 40, 12) == 0);
    @assert(ui_vnode_layout(image, UI_POSITION_RELATIVE_PARENT, 20, 5, 32, 16) == 0);
    @assert(UI_CHILD(root, text) == 0); @assert(UI_CHILD(root, image) == 0);
    ui_layout_result_t layout; memset(&layout,0,sizeof(layout)); @assert(ui_layout_result_init(&layout) == 0);
    @assert(ui_layout_run(root, 800, 600, &layout) == 0);
    @assert(layout.drawables.length == 3);
    @assert(layout.drawables.items[0].kind == UI_DRAWABLE_BOX);
    @assert(layout.drawables.items[0].as.box.children.length == 2);
    @assert(layout.drawables.items[0].as.box.children.items[0] == 1);
    @assert(layout.drawables.items[0].as.box.children.items[1] == 2);
    @assert(strcmp(layout.drawables.items[0].as.box.style->background, "#101010") == 0);
    @assert(layout.drawables.items[1].kind == UI_DRAWABLE_BOX);
    @assert(strcmp(layout.drawables.items[1].as.box.text, "hello") == 0);
    @assert(layout.drawables.items[2].kind == UI_DRAWABLE_IMAGE);
    @assert(layout.drawables.items[2].as.image.bitmap == &pixels);
    ui_layout_result_destroy(&layout); ui_vnode_destroy(root);
}

@test "printf renderer prints layout emissions" {
    int pixels = 1;
    UI_ELEMENT(root, "panel"); UI_TEXT(text, "hello"); UI_IMAGE(image, &pixels);
    @assert(ui_vnode_layout(root, UI_POSITION_RELATIVE_PARENT, 2, 1, 100, 50) == 0);
    @assert(ui_vnode_background(root, "blue") == 0);
    @assert(ui_vnode_layout(text, UI_POSITION_RELATIVE_PARENT, 4, 3, 40, 12) == 0);
    @assert(ui_vnode_layout(image, UI_POSITION_RELATIVE_PARENT, 20, 5, 32, 16) == 0);
    @assert(UI_CHILD(root, text) == 0); @assert(UI_CHILD(root, image) == 0);
    FILE* file = tmpfile(); @assert(file != NULL);
    ui_printf_renderer_state_t state; ui_renderer_t renderer = ui_printf_renderer(&state, file);
    @assert(ui_render_vdom(root, 800, 600, &renderer) == 0);
    rewind(file);
    char output[2048]; size_t n = fread(output,1,sizeof(output)-1,file); output[n] = '\0';
    @assert(strstr(output, "LAYOUT drawables=3") != NULL);
    @assert(strstr(output, "BOX node=0") != NULL);
    @assert(strstr(output, "x=1.00 y=2.00 w=100.00 h=50.00") != NULL);
    @assert(strstr(output, "background=\"blue\"") != NULL);
    @assert(strstr(output, "text=\"hello\"") != NULL);
    @assert(strstr(output, "IMAGE node=0") != NULL);
    fclose(file); ui_vnode_destroy(root);
}
