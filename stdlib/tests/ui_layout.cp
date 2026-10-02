#include <stdio.h>
#include <string.h>

comptime import "stdlib:/ui/printf_renderer.cp";

@test "vdom owns style objects and dynamic children" {
    ui_vnode_t* root = ui_vnode_t.element("panel");
    ui_vnode_t* child = ui_vnode_t.text("hello");
    @assert(root != NULL);
    @assert(child != NULL);
    @assert(root->children.add(child) == 0);
    @assert(root->children.size() == 1);
    @assert(*root->children.get(0) == child);

    @assert(root->style.layout.position_value == UI_POSITION_RELATIVE_PARENT);
    @assert(root->style.layout.top_value == 0.0);
    @assert(root->style.layout.left_value == 0.0);
    @assert(root->style.layout.width_value == 0.0);
    @assert(root->style.layout.height_value == 0.0);

    root->destroy();
}

@test "layout resolves parent relative geometry" {
    ui_vnode_t* root = ui_vnode_t.element("root");
    ui_vnode_t* child = ui_vnode_t.element("child");
    @assert(root != NULL && child != NULL);

    @assert(root->style.layout.set(UI_POSITION_RELATIVE_PARENT, 20, 10, 100, 80) != NULL);
    @assert(child->style.layout.set(UI_POSITION_RELATIVE_PARENT, 7, 5, 30, 40) != NULL);
    @assert(root->child(child) == 0);

    ui_layout_t layout;
    @assert(layout.init() == 0);
    @assert(layout.run(root, 800, 600) == 0);
    @assert(layout.result.drawables.length == 2);
    @assert(layout.result.drawables.items[0].as.box.rect.x == 10.0);
    @assert(layout.result.drawables.items[0].as.box.rect.y == 20.0);
    @assert(layout.result.drawables.items[1].as.box.rect.x == 15.0);
    @assert(layout.result.drawables.items[1].as.box.rect.y == 27.0);
    @assert(layout.result.drawables.items[1].as.box.rect.width == 30.0);
    @assert(layout.result.drawables.items[1].as.box.rect.height == 40.0);

    layout.destroy();
    root->destroy();
}

@test "relative sibling uses previous top left without flow" {
    ui_vnode_t* root = ui_vnode_t.element("root");
    ui_vnode_t* first = ui_vnode_t.element("first");
    ui_vnode_t* second = ui_vnode_t.element("second");
    @assert(root != NULL && first != NULL && second != NULL);

    root->style.layout.set(UI_POSITION_RELATIVE_PARENT, 100, 50, 400, 300);
    first->style.layout.set(UI_POSITION_RELATIVE_PARENT, 20, 10, 100, 80);
    second->style.layout.set(UI_POSITION_RELATIVE_SIBLING, 4, 3, 20, 10);
    @assert(root->child(first) == 0);
    @assert(root->child(second) == 0);

    ui_layout_t layout;
    @assert(layout.init() == 0);
    @assert(layout.run(root, 800, 600) == 0);
    @assert(layout.result.drawables.length == 3);
    @assert(layout.result.drawables.items[1].as.box.rect.x == 60.0);
    @assert(layout.result.drawables.items[1].as.box.rect.y == 120.0);
    @assert(layout.result.drawables.items[2].as.box.rect.x == 63.0);
    @assert(layout.result.drawables.items[2].as.box.rect.y == 124.0);
    @assert(layout.result.drawables.items[2].as.box.rect.x != 160.0);

    layout.destroy();
    root->destroy();
}

@test "fixed geometry anchors to viewport" {
    ui_vnode_t* root = ui_vnode_t.element("root");
    ui_vnode_t* fixed = ui_vnode_t.element("fixed");
    root->style.layout.set(UI_POSITION_RELATIVE_PARENT, 100, 200, 400, 300);
    fixed->style.layout.set(UI_POSITION_FIXED, 11, 9, 20, 30);
    @assert(root->child(fixed) == 0);

    ui_layout_t layout;
    @assert(layout.init() == 0);
    @assert(layout.run(root, 800, 600) == 0);
    @assert(layout.result.drawables.items[1].as.box.rect.x == 9.0);
    @assert(layout.result.drawables.items[1].as.box.rect.y == 11.0);

    layout.destroy();
    root->destroy();
}

@test "layout emits styled boxes text child indexes and image" {
    int bitmap = 7;
    ui_vnode_t* root = ui_vnode_t.element("panel");
    ui_vnode_t* text = ui_vnode_t.text("hello");
    ui_vnode_t* image = ui_vnode_t.image(&bitmap);

    root->style.layout.set(UI_POSITION_RELATIVE_PARENT, 2, 1, 100, 50);
    @assert(root->style.graphics.background("#101010") == 0);
    @assert(root->style.graphics.color("white") == 0);
    @assert(root->style.graphics.border("1 solid gray") == 0);
    @assert(root->style.graphics.font_family("mono") == 0);
    root->style.graphics.font_size(14);
    root->style.graphics.font_weight(700);
    root->style.graphics.font_decoration(UI_FONT_DECORATION_UNDERLINE);

    text->style.layout.set(UI_POSITION_RELATIVE_PARENT, 4, 3, 40, 12);
    image->style.layout.set(UI_POSITION_RELATIVE_PARENT, 20, 5, 32, 16);
    @assert(root->child(text) == 0);
    @assert(root->child(image) == 0);

    ui_layout_t layout;
    @assert(layout.init() == 0);
    @assert(layout.run(root, 800, 600) == 0);
    @assert(layout.result.drawables.length == 3);
    @assert(layout.result.drawables.items[0].kind == UI_DRAWABLE_BOX);
    @assert(layout.result.drawables.items[0].as.box.children.length == 2);
    @assert(layout.result.drawables.items[0].as.box.children.items[0] == 1);
    @assert(layout.result.drawables.items[0].as.box.children.items[1] == 2);
    @assert(strcmp(layout.result.drawables.items[0].as.box.style->background_value.data, "#101010") == 0);
    @assert(strcmp(layout.result.drawables.items[1].as.box.text, "hello") == 0);
    @assert(layout.result.drawables.items[2].kind == UI_DRAWABLE_IMAGE);
    @assert(layout.result.drawables.items[2].as.image.bitmap == &bitmap);

    layout.destroy();
    root->destroy();
}

@test "printf renderer prints layout emissions" {
    int bitmap = 1;
    ui_vnode_t* root = ui_vnode_t.element("panel");
    ui_vnode_t* text = ui_vnode_t.text("hello");
    ui_vnode_t* image = ui_vnode_t.image(&bitmap);
    root->style.layout.set(UI_POSITION_RELATIVE_PARENT, 2, 1, 100, 50);
    root->style.graphics.background("blue");
    text->style.layout.set(UI_POSITION_RELATIVE_PARENT, 4, 3, 40, 12);
    image->style.layout.set(UI_POSITION_RELATIVE_PARENT, 20, 5, 32, 16);
    root->child(text);
    root->child(image);

    FILE* file = tmpfile();
    @assert(file != NULL);
    ui_printf_renderer_t printer;
    @assert(printer.init(file) == 0);
    ui_renderer_t renderer = printer.interface();
    @assert(renderer.render(root, 800, 600) == 0);

    rewind(file);
    char output[2048];
    size_t count = fread(output, 1, sizeof(output) - 1, file);
    output[count] = '\0';
    @assert(strstr(output, "LAYOUT drawables=3") != NULL);
    @assert(strstr(output, "x=1.00 y=2.00 w=100.00 h=50.00") != NULL);
    @assert(strstr(output, "background=\"blue\"") != NULL);
    @assert(strstr(output, "text=\"hello\"") != NULL);
    @assert(strstr(output, "IMAGE") != NULL);

    fclose(file);
    root->destroy();
}
