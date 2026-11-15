    // Flux loading screen: the Mojang Studios logo is not drawn (gen_loading.py)
    if (textureSize(Sampler0, 0) == ivec2(512, 512)
            && texelFetch(Sampler0, ivec2(8, 45), 0).a > 0.99
            && texelFetch(Sampler0, ivec2(49, 45), 0).a > 0.99
            && texelFetch(Sampler0, ivec2(90, 45), 0).a > 0.99
            && texelFetch(Sampler0, ivec2(8, 8), 0).a < 0.01
            && texelFetch(Sampler0, ivec2(49, 8), 0).a < 0.01
            && texelFetch(Sampler0, ivec2(90, 8), 0).a < 0.01) discard;
