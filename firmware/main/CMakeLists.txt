idf_component_register(SRCS "app_hf_msg_set.c"
                            "adpcm.c"
                            "audio_pipe.c"
                            "ble_gatt.c"
                            "link_policy.c"
                            "spp_link.c"
                            "bt_app_core.c"
                            "bt_app_hf.c"
                            "main.c"
                    INCLUDE_DIRS ".")

# link_policy.c reaches into Bluedroid's BTM layer (no public API for link policy)
idf_component_get_property(bt_dir bt COMPONENT_DIR)
target_include_directories(${COMPONENT_LIB} PRIVATE
    ${bt_dir}/host/bluedroid/stack/include
    ${bt_dir}/host/bluedroid/common/include
    ${bt_dir}/host/bluedroid/bta/include
    ${bt_dir}/host/bluedroid/stack/l2cap/include
    ${bt_dir}/host/bluedroid/stack/rfcomm/include
    ${bt_dir}/common/include
    ${bt_dir}/common/osi/include)
