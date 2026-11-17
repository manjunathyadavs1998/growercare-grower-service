-- =====================================================================================================
-- DEV/TEST ONLY: placeholder notice texts (SPEC-GRW-06 D2). Real texts come from legal per country and language.
-- =====================================================================================================
UPDATE ref.consent_notice n
   SET title = t.title || ' (' || n.version || ')',
       body  = t.body
  FROM (VALUES
        ('en', 'SERVICE',               'GrowerCare service',                 'PLACEHOLDER. We use your name, phone and farm details to handle your requests and cases.'),
        ('en', 'DATA_SHARING_RETAILER', 'Sharing with your retailer',         'PLACEHOLDER. Your retailer may see your cases so they can help you.'),
        ('en', 'MARKETING',             'Offers and news',                    'PLACEHOLDER. We may send you offers and agronomy news. You can stop this any time.'),
        ('hi', 'SERVICE',               'GrowerCare सेवा',                      'PLACEHOLDER. आपके अनुरोधों और मामलों के लिए हम आपका नाम, फ़ोन और खेत की जानकारी उपयोग करते हैं।'),
        ('hi', 'DATA_SHARING_RETAILER', 'आपके विक्रेता के साथ साझा करना',          'PLACEHOLDER. आपका विक्रेता आपकी मदद के लिए आपके मामले देख सकता है।'),
        ('hi', 'MARKETING',             'ऑफ़र और समाचार',                        'PLACEHOLDER. हम आपको ऑफ़र और कृषि समाचार भेज सकते हैं। आप इसे कभी भी रोक सकते हैं।'),
        ('mr', 'SERVICE',               'GrowerCare सेवा',                      'PLACEHOLDER. तुमच्या विनंत्या आणि प्रकरणांसाठी आम्ही तुमचे नाव, फोन आणि शेताची माहिती वापरतो.'),
        ('mr', 'DATA_SHARING_RETAILER', 'तुमच्या विक्रेत्यासोबत शेअर करणे',          'PLACEHOLDER. तुमचा विक्रेता तुम्हाला मदत करण्यासाठी तुमची प्रकरणे पाहू शकतो.'),
        ('mr', 'MARKETING',             'ऑफर आणि बातम्या',                       'PLACEHOLDER. आम्ही तुम्हाला ऑफर आणि कृषी बातम्या पाठवू शकतो. तुम्ही हे कधीही थांबवू शकता.'),
        ('pt', 'SERVICE',               'Serviço GrowerCare',                 'PLACEHOLDER. Usamos seu nome, telefone e dados da fazenda para tratar seus pedidos e casos.'),
        ('pt', 'DATA_SHARING_RETAILER', 'Compartilhar com sua revenda',       'PLACEHOLDER. Sua revenda pode ver seus casos para ajudar você.'),
        ('pt', 'MARKETING',             'Ofertas e novidades',                'PLACEHOLDER. Podemos enviar ofertas e novidades agronômicas. Você pode parar quando quiser.')
       ) AS t (language, purpose, title, body)
 WHERE n.language = t.language AND n.purpose = t.purpose;
